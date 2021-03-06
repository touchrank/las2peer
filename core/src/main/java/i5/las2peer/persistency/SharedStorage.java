package i5.las2peer.persistency;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

import i5.las2peer.api.Configurable;
import i5.las2peer.api.persistency.EnvelopeAlreadyExistsException;
import i5.las2peer.api.persistency.EnvelopeException;
import i5.las2peer.api.persistency.EnvelopeNotFoundException;
import i5.las2peer.logging.L2pLogger;
import i5.las2peer.persistency.helper.ArtifactPartComparator;
import i5.las2peer.persistency.helper.FetchEnvelopeHelper;
import i5.las2peer.persistency.helper.FetchHashedHelper;
import i5.las2peer.persistency.helper.LatestArtifactVersionFinder;
import i5.las2peer.persistency.helper.MergeCounter;
import i5.las2peer.persistency.helper.MultiArtifactHandler;
import i5.las2peer.persistency.helper.MultiStoreResult;
import i5.las2peer.persistency.helper.StoreProcessHelper;
import i5.las2peer.persistency.pastry.PastFetchContinuation;
import i5.las2peer.persistency.pastry.PastInsertContinuation;
import i5.las2peer.persistency.pastry.PastLookupContinuation;
import i5.las2peer.security.AgentImpl;
import i5.las2peer.serialization.SerializationException;
import i5.las2peer.serialization.SerializeTools;
import i5.las2peer.tools.CryptoException;
import rice.p2p.commonapi.Id;
import rice.p2p.commonapi.IdFactory;
import rice.p2p.commonapi.Node;
import rice.p2p.past.PastContentHandle;
import rice.p2p.past.PastImpl;
import rice.p2p.past.PastPolicy.DefaultPastPolicy;
import rice.pastry.commonapi.PastryIdFactory;
import rice.persistence.Cache;
import rice.persistence.LRUCache;
import rice.persistence.MemoryStorage;
import rice.persistence.PersistentStorage;
import rice.persistence.Storage;
import rice.persistence.StorageManagerImpl;

public class SharedStorage extends Configurable implements L2pStorageInterface {

	/**
	 * Storage mode for the pastry node &ndash; either use only memory or the file system for stored artifacts.
	 */
	public enum STORAGE_MODE {
		FILESYSTEM,
		MEMORY,
	}

	private static final L2pLogger logger = L2pLogger.getInstance(SharedStorage.class);

	public static final int DEFAULT_NUM_OF_REPLICAS = 5;
	private int numOfReplicas = DEFAULT_NUM_OF_REPLICAS;

	public static final int DEFAULT_MAXIMUM_CACHE_SIZE = 200 * 1024 * 1024; // 200 MB
	private int maximumCacheSize = DEFAULT_MAXIMUM_CACHE_SIZE;

	public static final String DEFAULT_STORAGE_ROOT_DIR = "node-storage" + File.separator;
	private String storageRootDir = DEFAULT_STORAGE_ROOT_DIR;

	public static final long DEFAULT_MAXIMUM_STORAGE_SIZE = 1000 * 1024 * 1024; // 1 GB
	private long maximumStorageSize = DEFAULT_MAXIMUM_STORAGE_SIZE;

	public static final long DEFAULT_ASYNC_INSERT_OPERATION_TIMEOUT = 5 * 60 * 1000 * 1000; // ms => 5 min
	private long asyncInsertOperationTimeout = DEFAULT_ASYNC_INSERT_OPERATION_TIMEOUT;

	private final PastImpl pastStorage;
	private final PastryIdFactory artifactIdFactory;
	private final ExecutorService threadpool;
	private final ConcurrentHashMap<String, Long> versionCache;

	public SharedStorage(Node node, STORAGE_MODE storageMode, ExecutorService threadpool, String storageDir)
			throws EnvelopeException {
		IdFactory pastIdFactory = new PastryIdFactory(node.getEnvironment());
		Storage storage;
		if (storageMode == STORAGE_MODE.MEMORY) {
			storage = new MemoryStorage(pastIdFactory);
		} else if (storageMode == STORAGE_MODE.FILESYSTEM) {
			if (storageDir != null) {
				storageRootDir = storageDir;
			}
			if (!storageRootDir.endsWith(File.separator)) {
				storageRootDir += File.separator;
			}
			try {
				logger.info("loading storage...");
				long start = System.currentTimeMillis();
				storage = new PersistentStorage(pastIdFactory, storageRootDir + "node_" + node.getId().toStringFull(),
						maximumStorageSize, node.getEnvironment());
				long timediff = System.currentTimeMillis() - start;
				logger.info("storage ready, loading took " + timediff + "ms");
			} catch (IOException e) {
				throw new EnvelopeException("Could not initialize persistent storage!", e);
			}
		} else {
			throw new EnvelopeException("Unexpected storage mode '" + storageMode + "'");
		}
		Cache cache = new LRUCache(storage, maximumCacheSize, node.getEnvironment());
		StorageManagerImpl manager = new StorageManagerImpl(pastIdFactory, storage, cache);
		pastStorage = new PastImpl(node, manager, numOfReplicas, "i5.las2peer.enterprise.storage",
				new DefaultPastPolicy());
		artifactIdFactory = new PastryIdFactory(node.getEnvironment());
		this.threadpool = threadpool;
		versionCache = new ConcurrentHashMap<>();
	}

	public long getLocalSize() {
		return pastStorage.getStorageManager().getTotalSize();
	}

	public long getLocalMaxSize() {
		Storage storage = pastStorage.getStorageManager().getStorage();
		if (storage instanceof MemoryStorage) {
			// return max ram size
			return Runtime.getRuntime().maxMemory();
		} else if (storage instanceof PersistentStorage) {
			return maximumStorageSize;
		} else {
			logger.severe("Unknown storage type. Could not determine storage size");
			return 0;
		}
	}

	private void lookupHandles(Id id, StorageLookupHandler lookupHandler, StorageExceptionHandler exceptionHandler) {
		pastStorage.lookupHandles(id, numOfReplicas + 1,
				new PastLookupContinuation(threadpool, lookupHandler, exceptionHandler));
	}

	private void waitForStoreResult(StoreProcessHelper resultHelper, long timeoutMs) throws EnvelopeException {
		long startWait = System.currentTimeMillis();
		while (System.currentTimeMillis() - startWait < timeoutMs) {
			try {
				if (resultHelper.getResult() >= 0) {
					return;
				}
				Thread.sleep(1);
			} catch (EnvelopeException e) {
				throw e;
			} catch (Exception e) {
				throw new EnvelopeException(e);
			}
		}
		throw new EnvelopeException("store operation timed out");
	}

	@Override
	public EnvelopeVersion createEnvelope(String identifier, PublicKey expectedAuthorPubKey, Serializable content,
			AgentImpl... readers) throws IllegalArgumentException, SerializationException, CryptoException {
		return new EnvelopeVersion(identifier, expectedAuthorPubKey, content, Arrays.asList(readers));
	}

	@Override
	public EnvelopeVersion createEnvelope(String identifier, PublicKey expectedAuthorPubKey, Serializable content,
			Collection<?> readers) throws IllegalArgumentException, SerializationException, CryptoException {
		return new EnvelopeVersion(identifier, expectedAuthorPubKey, content, readers);
	}

	@Override
	public EnvelopeVersion createEnvelope(EnvelopeVersion previousVersion, Serializable content)
			throws IllegalArgumentException, SerializationException, CryptoException {
		return new EnvelopeVersion(previousVersion, content);
	}

	@Override
	public EnvelopeVersion createEnvelope(EnvelopeVersion previousVersion, Serializable content, AgentImpl... readers)
			throws IllegalArgumentException, SerializationException, CryptoException {
		return new EnvelopeVersion(previousVersion, content, Arrays.asList(readers));
	}

	@Override
	public EnvelopeVersion createEnvelope(EnvelopeVersion previousVersion, Serializable content, Collection<?> readers)
			throws IllegalArgumentException, SerializationException, CryptoException {
		return new EnvelopeVersion(previousVersion, content, readers);
	}

	@Override
	public EnvelopeVersion createUnencryptedEnvelope(String identifier, PublicKey expectedAuthorPubKey,
			Serializable content) throws IllegalArgumentException, SerializationException, CryptoException {
		return new EnvelopeVersion(identifier, expectedAuthorPubKey, content, new ArrayList<AgentImpl>());
	}

	@Override
	public EnvelopeVersion createUnencryptedEnvelope(EnvelopeVersion previousVersion, Serializable content)
			throws IllegalArgumentException, SerializationException, CryptoException {
		return new EnvelopeVersion(previousVersion, content, new ArrayList<AgentImpl>());
	}

	@Override
	public void storeEnvelope(EnvelopeVersion envelope, AgentImpl author, long timeoutMs) throws EnvelopeException {
		if (timeoutMs < 0) {
			throw new IllegalArgumentException("Timeout must be greater or equal to zero");
		}
		StoreProcessHelper resultHelper = new StoreProcessHelper();
		storeEnvelopeAsync(envelope, author, resultHelper, resultHelper, resultHelper);
		waitForStoreResult(resultHelper, timeoutMs);
	}

	@Override
	public void storeEnvelopeAsync(EnvelopeVersion envelope, AgentImpl author, StorageStoreResultHandler resultHandler,
			StorageCollisionHandler collisionHandler, StorageExceptionHandler exceptionHandler) {
		logger.info("Storing envelope " + envelope + " ...");
		// insert envelope into DHT
		final MergeCounter mergeCounter = new MergeCounter();
		storeEnvelopeAsync(envelope, author, resultHandler, collisionHandler, exceptionHandler, mergeCounter);
	}

	private void storeEnvelopeAsync(EnvelopeVersion envelope, AgentImpl author, StorageStoreResultHandler resultHandler,
			StorageCollisionHandler collisionHandler, StorageExceptionHandler exceptionHandler,
			MergeCounter mergeCounter) {
		// check for collision to offer merging
		threadpool.execute(new Runnable() {
			@Override
			public void run() {
				logger.fine("Checking collision for " + envelope.toString());
				Id metadataId = MetadataArtifact.buildMetadataId(artifactIdFactory, envelope.getIdentifier(),
						envelope.getVersion());
				lookupHandles(metadataId, new StorageLookupHandler() {
					@Override
					public void onLookup(ArrayList<PastContentHandle> metadataHandles) {
						if (metadataHandles.size() > 0) {
							// collision detected
							handleCollision(envelope, author, metadataHandles, resultHandler, collisionHandler,
									exceptionHandler, mergeCounter);
						} else {
							// no collision -> try insert
							insertEnvelope(envelope, author, resultHandler, exceptionHandler);
						}
					}
				}, exceptionHandler);
			}
		});
	}

	private void handleCollision(EnvelopeVersion envelope, AgentImpl author,
			ArrayList<PastContentHandle> metadataHandles, StorageStoreResultHandler resultHandler,
			StorageCollisionHandler collisionHandler, StorageExceptionHandler exceptionHandler,
			MergeCounter mergeCounter) {
		if (collisionHandler != null) {
			fetchWithMetadata(metadataHandles, new StorageEnvelopeHandler() {
				@Override
				public void onEnvelopeReceived(EnvelopeVersion inNetwork) {
					try {
						Serializable mergedContent = collisionHandler.onCollision(envelope, inNetwork,
								mergeCounter.value());
						long mergedVersion = Math.max(envelope.getVersion(), inNetwork.getVersion()) + 1;
						Set<PublicKey> mergedReaders = collisionHandler.mergeReaders(envelope.getReaderKeys().keySet(),
								inNetwork.getReaderKeys().keySet());
						Set<String> mergedGroups = collisionHandler.mergeGroups(envelope.getReaderGroupIds(),
								inNetwork.getReaderGroupIds());
						EnvelopeVersion mergedEnv = new EnvelopeVersion(envelope.getIdentifier(), mergedVersion,
								author.getPublicKey(), mergedContent, mergedReaders, mergedGroups);
						logger.info("Merged envelope (collisions: " + mergeCounter.value() + ") from network "
								+ inNetwork.toString() + " with local copy " + envelope.toString()
								+ " to merged version " + mergedEnv.toString());
						mergeCounter.increase();
						storeEnvelopeAsync(mergedEnv, author, resultHandler, collisionHandler, exceptionHandler,
								mergeCounter);
					} catch (StopMergingException | IllegalArgumentException | SerializationException
							| CryptoException e) {
						exceptionHandler.onException(e);
					}
				}
			}, exceptionHandler);
		} else if (exceptionHandler != null) {
			exceptionHandler.onException(
					new EnvelopeAlreadyExistsException("Duplicate DHT entry for envelope " + envelope.toString()));
		}
	}

	private void insertEnvelope(EnvelopeVersion envelope, AgentImpl author, StorageStoreResultHandler resultHandler,
			StorageExceptionHandler exceptionHandler) {
		final long version = envelope.getVersion();
		if (version < 1) { // just to be sure
			if (exceptionHandler != null) {
				exceptionHandler.onException(
						new IllegalArgumentException("Version number (" + version + ") must be higher than zero"));
			}
			return;
		}
		// XXX only accept envelope if the content has changed?
		logger.fine("Inserting parted envelope into network DHT");
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try {
			ObjectOutputStream oos = new ObjectOutputStream(baos);
			oos.writeObject(envelope);
			baos.close();
		} catch (IOException e) {
			if (exceptionHandler != null) {
				exceptionHandler.onException(e);
			}
			// cancel insert operation
			return;
		}
		byte[] serialized = baos.toByteArray();
		int size = serialized.length;
		int parts = size / NetworkArtifact.MAX_SIZE;
		if (parts * NetworkArtifact.MAX_SIZE < size) {
			parts++; // this happens if max size is not a divider of size
		}
		int partsize = size / parts + 1;
		logger.fine("Given object is serialized " + size + " bytes heavy, split into " + parts + " parts each "
				+ partsize + " bytes in size");
		MultiStoreResult multiResult = new MultiStoreResult(parts);
		final String identifier = envelope.getIdentifier();
		try {
			int offset = 0;
			for (int part = 0; part < parts; part++) {
				byte[] rawPart = Arrays.copyOfRange(serialized, offset, offset + partsize);
				NetworkArtifact toStore = new EnvelopeArtifact(artifactIdFactory, identifier, part, rawPart, author);
				logger.fine("Storing part " + part + " for envelope " + envelope + " with id "
						+ toStore.getId().toStringFull());
				pastStorage.insert(toStore, new PastInsertContinuation(threadpool, multiResult, multiResult, toStore));
				offset += partsize;
			}
		} catch (Exception e) {
			if (exceptionHandler != null) {
				exceptionHandler.onException(e);
			}
			return;
		}
		// wait for all part inserts
		long insertStart = System.currentTimeMillis();
		while (!multiResult.isDone()) {
			if (System.currentTimeMillis() - insertStart >= asyncInsertOperationTimeout) {
				// this point means the network layer did not receive positive or negative feedback
				if (exceptionHandler != null) {
					exceptionHandler.onException(new EnvelopeException("Network communication timeout"));
				}
				return;
			}
			try {
				Thread.sleep(1);
			} catch (InterruptedException e) {
				if (exceptionHandler != null) {
					exceptionHandler.onException(e);
				}
				return;
			}
		}
		Exception exception = multiResult.getException();
		if (exception != null) {
			if (exceptionHandler != null) {
				exceptionHandler.onException(exception);
			}
			return;
		}
		// all parts done? insert MetadataEnvelope to complete insert operation
		try {
			MetadataEnvelope metadataEnvelope = new MetadataEnvelope(identifier, version, parts);
			NetworkArtifact metadataArtifact = new MetadataArtifact(artifactIdFactory, identifier, version,
					SerializeTools.serialize(metadataEnvelope), author);
			logger.fine("Storing metadata for envelope " + metadataEnvelope.toString() + " with id "
					+ metadataArtifact.getId().toStringFull());
			pastStorage.insert(metadataArtifact,
					new PastInsertContinuation(threadpool, new StorageStoreResultHandler() {
						@Override
						public void onResult(Serializable envelope, int successfulOperations) {
							// all done - call actual user defined result handlers
							Exception e = multiResult.getException();
							if (e != null) {
								if (exceptionHandler != null) {
									exceptionHandler.onException(e);
								}
							} else if (resultHandler != null) {
								resultHandler.onResult(envelope, multiResult.getMinSuccessfulOperations());
							}
						}
					}, exceptionHandler, metadataArtifact));
		} catch (Exception e) {
			if (exceptionHandler != null) {
				exceptionHandler.onException(e);
			}
			return;
		}
	}

	@Override
	public EnvelopeVersion fetchEnvelope(String identifier, long timeoutMs)
			throws EnvelopeNotFoundException, EnvelopeException {
		return fetchEnvelope(identifier, EnvelopeVersion.LATEST_VERSION, timeoutMs);
	}

	public EnvelopeVersion fetchEnvelope(String identifier, long version, long timeoutMs)
			throws EnvelopeNotFoundException, EnvelopeException {
		if (timeoutMs < 0) {
			throw new IllegalArgumentException("Timeout must be greater or equal to zero");
		}
		FetchEnvelopeHelper resultHelper = new FetchEnvelopeHelper();
		fetchEnvelopeAsync(identifier, version, resultHelper, resultHelper);
		long startWait = System.currentTimeMillis();
		while (System.currentTimeMillis() - startWait < timeoutMs) {
			try {
				EnvelopeVersion result = resultHelper.getResult();
				if (result != null) {
					return result;
				}
				Thread.sleep(1);
			} catch (EnvelopeException e) {
				throw e;
			} catch (Exception e) {
				throw new EnvelopeException(e);
			}
		}
		throw new EnvelopeException("Fetch operation time out");
	}

	@Override
	public void fetchEnvelopeAsync(String identifier, StorageEnvelopeHandler envelopeHandler,
			StorageExceptionHandler exceptionHandler) {
		fetchEnvelopeAsync(identifier, EnvelopeVersion.LATEST_VERSION, envelopeHandler, exceptionHandler);
	}

	public void fetchEnvelopeAsync(String identifier, long version, StorageEnvelopeHandler envelopeHandler,
			StorageExceptionHandler exceptionHandler) {
		if (envelopeHandler == null) {
			// fetching something without caring about the result makes no sense
			if (exceptionHandler != null) {
				exceptionHandler.onException(new NullPointerException("envelope handler must not be null"));
			}
			return;
		}
		// get handles for first part of the desired version
		if (version == EnvelopeVersion.LATEST_VERSION) {
			// retrieve the latest version from the network
			Long startVersion = versionCache.get(identifier);
			if (startVersion == null) {
				startVersion = EnvelopeVersion.START_VERSION;
			}
			logger.fine("Starting latest version lookup for " + identifier + " at " + startVersion);
			threadpool.execute(new LatestArtifactVersionFinder(identifier, startVersion, new StorageLookupHandler() {
				@Override
				public void onLookup(ArrayList<PastContentHandle> metadataHandles) {
					if (metadataHandles.size() > 0) {
						fetchWithMetadata(metadataHandles, new StorageEnvelopeHandler() {
							@Override
							public void onEnvelopeReceived(EnvelopeVersion result) {
								// this handler-in-the-middle updates the version cache,
								// before returning the result to the actual envelope handler
								versionCache.put(result.getIdentifier(), result.getVersion());
								envelopeHandler.onEnvelopeReceived(result);
							}
						}, exceptionHandler);
					} else {
						// not found
						if (exceptionHandler != null) {
							exceptionHandler.onException(new EnvelopeNotFoundException(
									"no version found for identifier '" + identifier + "'"));
						}
					}
				}
			}, artifactIdFactory, pastStorage, numOfReplicas + 1, threadpool));
		} else {
			Id checkId = MetadataArtifact.buildMetadataId(artifactIdFactory, identifier, version);
			lookupHandles(checkId, new StorageLookupHandler() {
				@Override
				public void onLookup(ArrayList<PastContentHandle> metadataHandles) {
					if (metadataHandles.size() > 0) {
						// call from first part
						fetchWithMetadata(metadataHandles, envelopeHandler, exceptionHandler);
					} else {
						// not found
						if (exceptionHandler != null) {
							exceptionHandler.onException(new EnvelopeNotFoundException(
									"Envelope with identifier '" + identifier + "' and version " + version + " ("
											+ checkId.toStringFull() + ") not found in shared storage!"));
						}
					}
				}

			}, exceptionHandler);
		}
	}

	private void fetchWithMetadata(ArrayList<PastContentHandle> metadataHandles, StorageEnvelopeHandler envelopeHandler,
			StorageExceptionHandler exceptionHandler) {
		fetchFromHandles(metadataHandles, new StorageArtifactHandler() {
			@Override
			public void onReceive(AbstractArtifact artifact) {
				try {
					Serializable received = SerializeTools.deserialize(artifact.getContent());
					if (received instanceof MetadataEnvelope) {
						MetadataEnvelope metadata = (MetadataEnvelope) received;
						// metadata received query all actual data parts
						int size = metadata.getEnvelopeNumOfParts();
						MultiArtifactHandler artifactHandler = new MultiArtifactHandler(size,
								new StoragePartsHandler() {
									@Override
									public void onPartsReceived(ArrayList<NetworkArtifact> parts) {
										try {
											EnvelopeVersion result = buildFromParts(artifactIdFactory, metadata, parts);
											envelopeHandler.onEnvelopeReceived(result);
										} catch (IllegalArgumentException | EnvelopeException e) {
											if (exceptionHandler != null) {
												exceptionHandler.onException(e);
											}
										}
									}
								}, exceptionHandler);
						for (int partIndex = 0; partIndex < size; partIndex++) {
							fetchPart(metadata.getEnvelopeIdentifier(), partIndex, metadata.getEnvelopeVersion(),
									artifactHandler);
						}
					} else if (exceptionHandler != null) {
						exceptionHandler.onException(
								new EnvelopeException("expected " + MetadataEnvelope.class.getCanonicalName()
										+ " but got " + received.getClass().getCanonicalName() + " instead"));
					}
				} catch (SerializationException | VerificationFailedException e) {
					if (exceptionHandler != null) {
						exceptionHandler.onException(e);
					}
				}
			}
		}, exceptionHandler);
	}

	private void fetchPart(String identifier, int part, long version, MultiArtifactHandler artifactHandler) {
		Id checkId = EnvelopeArtifact.buildId(artifactIdFactory, identifier, part);
		logger.fine("Fetching part (" + part + ") of envelope '" + identifier + "' with id " + checkId.toStringFull()
				+ " ...");
		lookupHandles(checkId, new StorageLookupHandler() {
			@Override
			public void onLookup(ArrayList<PastContentHandle> handles) {
				logger.fine("Got " + handles.size() + " past handles for part (" + part + ") of '" + identifier + "'");
				if (handles.size() < 1) {
					artifactHandler.onException(new EnvelopeNotFoundException("Part (" + part + ") of '" + identifier
							+ "' with id (" + checkId.toStringFull() + ") not found in shared storage!"));
				} else {
					fetchFromHandles(handles, artifactHandler, artifactHandler);
				}
			}
		}, artifactHandler);
	}

	private void fetchFromHandles(ArrayList<PastContentHandle> handles, StorageArtifactHandler artifactHandler,
			StorageExceptionHandler exceptionHandler) {
		if (handles.isEmpty()) {
			throw new IllegalArgumentException("No handles to fetch given");
		}
		// XXX pick the best fitting handle depending on nodeid (web-of-trust) or distance
		PastContentHandle bestHandle = handles.get(new Random().nextInt(handles.size()));
		// query fetch command
		pastStorage.fetch(bestHandle, new PastFetchContinuation(threadpool, artifactHandler, exceptionHandler));
	}

	private static EnvelopeVersion buildFromParts(PastryIdFactory artifactIdFactory, MetadataEnvelope metadata,
			ArrayList<NetworkArtifact> artifacts) throws IllegalArgumentException, EnvelopeException {
		if (artifacts == null || artifacts.isEmpty()) {
			throw new EnvelopeException("No parts to build from");
		}
		// order parts
		Collections.sort(artifacts, ArtifactPartComparator.INSTANCE);
		try {
			String envelopeIdentifier = null;
			long envelopeVersion = EnvelopeVersion.NULL_VERSION;
			int lastPartIndex = -1;
			int numberOfParts = 0;
			PublicKey authorPubKey = null;
			// concat raw byte arrays
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			for (NetworkArtifact artifact : artifacts) {
				logger.fine("concating content of " + artifact.toString() + " with " + metadata.getEnvelopeNumOfParts()
						+ " parts");
				// check identifier
				String artifactIdentifier = metadata.getEnvelopeIdentifier();
				if (artifactIdentifier == null) {
					throw new EnvelopeException("Artifact identifier must not be null");
				} else if (envelopeIdentifier == null) {
					envelopeIdentifier = artifactIdentifier;
				} else if (!artifactIdentifier.equals(metadata.getEnvelopeIdentifier())) {
					throw new EnvelopeException("Aritfact identifier (" + artifactIdentifier
							+ ") did not match envelope identifier (" + envelopeIdentifier + ")");
				}
				// check version
				long artifactVersion = metadata.getEnvelopeVersion();
				if (artifactVersion < EnvelopeVersion.START_VERSION) {
					throw new EnvelopeException("Artifact version (" + artifactVersion + ") must be bigger than "
							+ EnvelopeVersion.START_VERSION);
				} else if (envelopeVersion == EnvelopeVersion.NULL_VERSION) {
					envelopeVersion = artifactVersion;
				} else if (envelopeVersion != artifactVersion) {
					throw new EnvelopeException("Not matching versions for artifact (" + artifactVersion
							+ ") and envelope (" + envelopeVersion + ")");
				}
				// check part index
				int partIndex = artifact.getPartIndex();
				if (partIndex > lastPartIndex + 1) {
					throw new EnvelopeException("Artifacts not sorted or missing part! Got " + partIndex
							+ " instead of " + (lastPartIndex + 1));
				}
				lastPartIndex++;
				// check number of parts
				int parts = metadata.getEnvelopeNumOfParts();
				if (parts < 1) {
					throw new EnvelopeException("Number of parts given is to low " + parts);
				} else if (numberOfParts == 0) {
					numberOfParts = parts;
				} else if (numberOfParts != parts) {
					throw new EnvelopeException("Number of parts from artifact (" + numberOfParts
							+ ") did not match with envelope parts number (" + parts + ")");
				}
				// check author public key
				try {
					PublicKey artifactAuthorPubKey = artifact.getAuthorPublicKey();
					if (authorPubKey == null) {
						authorPubKey = artifactAuthorPubKey;
					} else if (!authorPubKey.equals(artifactAuthorPubKey)) {
						throw new EnvelopeException("Parts auhtor public keys do not match");
					}
				} catch (VerificationFailedException e) {
					throw new EnvelopeException("Could not retrieve author public key from network artifact.");
				}
				try {
					byte[] rawContent = artifact.getContent();
					if (rawContent != null) {
						// add content to buffer
						baos.write(rawContent);
					}
				} catch (VerificationFailedException e) {
					throw new EnvelopeException("Could not retrieve content from part", e);
				}
			}
			byte[] bytes = baos.toByteArray();
			// deserialize object
			ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
			ObjectInputStream ois = new ObjectInputStream(bais);
			Object obj = ois.readObject();
			EnvelopeVersion result;
			if (obj instanceof EnvelopeVersion) {
				result = (EnvelopeVersion) obj;
			} else {
				throw new EnvelopeException("expected class " + EnvelopeVersion.class.getCanonicalName() + " but got "
						+ obj.getClass().getCanonicalName() + " instead");
			}
			if (authorPubKey == null || !authorPubKey.equals(result.getAuthorPublicKey())) {
				throw new EnvelopeException("Network artifacts and envelope have different authors");
			}
			return result;
		} catch (IOException | ClassNotFoundException | ClassCastException | IllegalArgumentException e) {
			throw new EnvelopeException("Building envelope from parts failed!", e);
		}
	}

	@Override
	public void removeEnvelope(String identifier) throws EnvelopeNotFoundException, EnvelopeException {
		throw new EnvelopeException("Delete not implemented in Past!");
	}

	public void storeHashedContentAsync(byte[] content, StorageStoreResultHandler resultHandler,
			StorageExceptionHandler exceptionHandler) {
		try {
			HashedArtifact toStore = new HashedArtifact(artifactIdFactory, content);
			logger.info("Storing hashed artifact with id " + toStore.getId().toStringFull());
			pastStorage.insert(toStore,
					new PastInsertContinuation(threadpool, resultHandler, exceptionHandler, toStore));
		} catch (Exception e) {
			if (exceptionHandler != null) {
				exceptionHandler.onException(e);
			}
			return;
		}
	}

	public void storeHashedContent(byte[] content, long timeoutMs) throws EnvelopeException {
		if (timeoutMs < 0) {
			throw new IllegalArgumentException("Timeout must be greater or equal to zero");
		}
		StoreProcessHelper resultHelper = new StoreProcessHelper();
		storeHashedContentAsync(content, resultHelper, resultHelper);
		waitForStoreResult(resultHelper, timeoutMs);
	}

	public void fetchHashedContentAsync(byte[] hash, StorageArtifactHandler artifactHandler,
			StorageExceptionHandler exceptionHandler) {
		try {
			if (artifactHandler == null) {
				// fetching something without caring about the result makes no sense
				if (exceptionHandler != null) {
					exceptionHandler.onException(new NullPointerException("artifact handler must not be null"));
				}
				return;
			}
			Id fetchId = HashedArtifact.buildIdFromHash(artifactIdFactory, hash);
			lookupHandles(fetchId, new StorageLookupHandler() {
				@Override
				public void onLookup(ArrayList<PastContentHandle> handles) {
					if (handles.size() > 0) {
						// XXX pick the best fitting handle depending on nodeid (web-of-trust) or distance
						PastContentHandle bestHandle = handles.get(new Random().nextInt(handles.size()));
						pastStorage.fetch(bestHandle,
								new PastFetchContinuation(threadpool, artifactHandler, exceptionHandler));
					} else {
						// not found
						if (exceptionHandler != null) {
							exceptionHandler
									.onException(new EnvelopeNotFoundException("Hashed content for base64 hash '"
											+ java.util.Base64.getEncoder().encodeToString(hash)
											+ "' not found in shared storage!"));
						}
					}
				}
			}, exceptionHandler);
		} catch (Exception e) {
			if (exceptionHandler != null) {
				exceptionHandler.onException(e);
			}
			return;
		}
	}

	public byte[] fetchHashedContent(byte[] hash, long timeoutMs) throws EnvelopeException {
		if (timeoutMs < 0) {
			throw new IllegalArgumentException("Timeout must be greater or equal to zero");
		}
		FetchHashedHelper resultHelper = new FetchHashedHelper();
		fetchHashedContentAsync(hash, resultHelper, resultHelper);
		long startWait = System.currentTimeMillis();
		while (System.currentTimeMillis() - startWait < timeoutMs) {
			try {
				HashedArtifact result = resultHelper.getResult();
				if (result != null) {
					return result.getContent();
				}
				Thread.sleep(1);
			} catch (EnvelopeException e) {
				throw e;
			} catch (Exception e) {
				throw new EnvelopeException(e);
			}
		}
		throw new EnvelopeException("Fetch operation time out");
	}

}
