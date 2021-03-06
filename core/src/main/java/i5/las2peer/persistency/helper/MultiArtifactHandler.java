package i5.las2peer.persistency.helper;

import java.util.ArrayList;

import i5.las2peer.persistency.AbstractArtifact;
import i5.las2peer.persistency.NetworkArtifact;
import i5.las2peer.persistency.StorageArtifactHandler;
import i5.las2peer.persistency.StorageExceptionHandler;
import i5.las2peer.persistency.StoragePartsHandler;

/**
 * This class is used to handle the lookup and retrieve process for a bunch of {@link NetworkArtifact}s. It gathers all
 * parts till it received the necessary amount specified by the first part given. After that it calls the respective
 * handlers given, same for exceptions.
 */
public class MultiArtifactHandler implements StorageArtifactHandler, StorageExceptionHandler {

	private final int numberOfParts;
	private final StoragePartsHandler partsHandler;
	private final StorageExceptionHandler exceptionHandler;
	private final ArrayList<NetworkArtifact> parts;
	private boolean isComplete;

	/**
	 * Initiates this lookup result collection with the given first part. The number of parts is retrieved from the
	 * given first part.
	 *
	 * @param numberOfParts The number of artifacts that must be retrieved to consider a full set.
	 * @param partsHandler The result handler that gets all retrieved parts as a list.
	 * @param exceptionHandler The exception handler that should be called, if an exception occurs.
	 */
	public MultiArtifactHandler(int numberOfParts, StoragePartsHandler partsHandler,
			StorageExceptionHandler exceptionHandler) {
		this.numberOfParts = numberOfParts;
		this.partsHandler = partsHandler;
		this.exceptionHandler = exceptionHandler;
		parts = new ArrayList<>(numberOfParts);
		isComplete = false;
	}

	@Override
	public void onReceive(AbstractArtifact artifact) {
		synchronized (this) {
			if (isComplete) {
				return;
			}
		}
		if (artifact instanceof NetworkArtifact) {
			synchronized (parts) {
				// TODO perform integrity checks on fetched objects, and fetch others on failure
				parts.add((NetworkArtifact) artifact);
				// check if complete and call actual envelopeHandler
				if (parts.size() >= numberOfParts) {
					isComplete = true;
					partsHandler.onPartsReceived(parts);
				}
			}
		} else {
			// XXX logging
		}
	}

	@Override
	public void onException(Exception e) {
		synchronized (this) {
			if (isComplete) {
				return;
			}
		}
		// TODO try all handles before throwing an exception
		exceptionHandler.onException(e);
	}

}
