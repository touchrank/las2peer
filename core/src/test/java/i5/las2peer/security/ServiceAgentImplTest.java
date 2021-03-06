package i5.las2peer.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;

import org.junit.Test;

import i5.las2peer.api.p2p.ServiceNameVersion;
import i5.las2peer.api.security.AgentAccessDeniedException;
import i5.las2peer.api.security.AgentException;
import i5.las2peer.api.security.AgentOperationFailedException;
import i5.las2peer.communication.Message;
import i5.las2peer.communication.ServiceDiscoveryContent;
import i5.las2peer.p2p.AgentAlreadyRegisteredException;
import i5.las2peer.p2p.LocalNode;
import i5.las2peer.p2p.LocalNodeManager;
import i5.las2peer.p2p.TimeoutException;
import i5.las2peer.persistency.EncodingFailedException;
import i5.las2peer.serialization.MalformedXMLException;
import i5.las2peer.serialization.SerializationException;
import i5.las2peer.testing.MockAgentFactory;
import i5.las2peer.tools.CryptoException;

public class ServiceAgentImplTest {
	private static final String servicename = "i5.las2peer.somePackage.AService";
	private static final String serviceversion = "1.0";
	private static final String passphrase = "a passphrase";

	@Test
	public void testCreation() throws CryptoException, InternalSecurityException, AgentAccessDeniedException,
			AgentOperationFailedException {
		ServiceAgentImpl testee = ServiceAgentImpl
				.createServiceAgent(ServiceNameVersion.fromString(servicename + "@" + serviceversion), passphrase);

		assertEquals(servicename + "@" + serviceversion, testee.getServiceNameVersion().toString());

		assertTrue(testee.isLocked());

		try {
			testee.unlock("dummy");
			fail("Exception expected!");
		} catch (AgentAccessDeniedException e) {
			// intended
			assertTrue(testee.isLocked());
		}

		testee.unlock(passphrase);

		assertFalse(testee.isLocked());
	}

	@Test
	public void testXmlAndBack() throws CryptoException, InternalSecurityException, MalformedXMLException,
			AgentAccessDeniedException, AgentOperationFailedException {
		ServiceAgentImpl testee = ServiceAgentImpl
				.createServiceAgent(ServiceNameVersion.fromString(servicename + "@" + serviceversion), passphrase);

		String xml = testee.toXmlString();

		ServiceAgentImpl andBack = ServiceAgentImpl.createFromXml(xml);

		andBack.unlock(passphrase);
	}

	@Test
	public void testServiceDiscovery()
			throws CryptoException, InternalSecurityException, AgentAlreadyRegisteredException, AgentException,
			MalformedXMLException, IOException, EncodingFailedException, SerializationException, InterruptedException,
			TimeoutException, AgentAccessDeniedException {

		// start node
		LocalNode node = new LocalNodeManager().launchNode();

		node.startService(ServiceNameVersion.fromString("i5.las2peer.api.TestService@1.0"), "a pass");

		node.startService(ServiceNameVersion.fromString("i5.las2peer.api.TestService@1.1"), "a pass");

		node.startService(ServiceNameVersion.fromString("i5.las2peer.api.TestService@2.0"), "a pass");

		node.startService(ServiceNameVersion.fromString("i5.las2peer.api.TestService2@1.0"), "a pass");

		PassphraseAgentImpl userAgent = MockAgentFactory.getAdam();
		userAgent.unlock("adamspass");
		node.registerReceiver(userAgent);

		// invoke (fits)
		Message request = new Message(userAgent, ServiceAgentImpl.serviceNameToTopicId("i5.las2peer.api.TestService"),
				new ServiceDiscoveryContent(ServiceNameVersion.fromString("i5.las2peer.api.TestService@1"), false),
				30000);

		Message[] answers = node.sendMessageAndCollectAnswers(request, 4);

		assertEquals(2, answers.length);

		boolean found10 = false, found11 = false;
		for (Message m : answers) {
			m.open(userAgent, node);
			ServiceDiscoveryContent c = (ServiceDiscoveryContent) m.getContent();
			if (c.getService().getVersion().toString().equals("1.0")) {
				found10 = true;
			} else if (c.getService().getVersion().toString().equals("1.1")) {
				found11 = true;
			}
		}
		assertTrue(found10);
		assertTrue(found11);

		// invoke (exact)

		request = new Message(userAgent, ServiceAgentImpl.serviceNameToTopicId("i5.las2peer.api.TestService"),
				new ServiceDiscoveryContent(ServiceNameVersion.fromString("i5.las2peer.api.TestService@1.1"), true),
				30000);

		answers = node.sendMessageAndCollectAnswers(request, 4);

		assertEquals(1, answers.length);

		answers[0].open(userAgent, node);
		ServiceDiscoveryContent c = (ServiceDiscoveryContent) answers[0].getContent();
		assertTrue(c.getService().getVersion().toString().equals("1.1"));

	}
}
