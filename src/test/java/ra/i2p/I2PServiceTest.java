package ra.i2p;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import ra.common.network.NetworkPeer;

import java.util.Properties;
import java.util.logging.Logger;

public class I2PServiceTest {

    private static final Logger LOG = Logger.getLogger(I2PServiceTest.class.getName());

    private static NetworkPeer orig;
    private static NetworkPeer dest;
    private static MockProducerClient mockProducerClient;
    private static I2PEmbeddedService service;
    private static MockProducerService mockProducerService;
    private static Properties props;
    private static boolean serviceRunning = false;

    @BeforeClass
    public static void init() {
        LOG.info("Init...");
        props = new Properties();
        props.put("ra.tor.privkey.destroy", "true");
        mockProducerClient = new MockProducerClient();
//        service = new I2PService(mockProducerClient, null);
//        service.start(props);
//        Wait.aMin(2); // Wait 2 minutes for I2P network to warm up
    }

    @AfterClass
    public static void tearDown() {
        LOG.info("Teardown...");
        service.gracefulShutdown();
    }

//    @Test
//    public void initializedTest() {
//        Assert.assertTrue(serviceRunning);
//    }

    /**
     * Send an op message to the hidden service and verify op reply.
     */
//    @Test
//    public void peer2Peer() {
//        Envelope e = Envelope.documentFactory();
//        e.addExternalRoute(TORClientService.class, HTTPClientService.OPERATION_SEND, e, orig, dest);
//        e.mark("op", e);
//        // Ratchet route for testing
//        e.getDynamicRoutingSlip().nextRoute();
//        service.handleDocument(e);
//        Assert.assertTrue("{op=200}".equals(e.getContent()));
//    }
}
