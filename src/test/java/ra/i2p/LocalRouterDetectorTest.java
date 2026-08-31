package ra.i2p;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.net.ServerSocket;

public class LocalRouterDetectorTest {

    @Test
    public void reportsNoRouterOnAnUnusedPort() {
        // Very unlikely anything is listening here.
        LocalRouterDetector d = new LocalRouterDetector("127.0.0.1", 7699, 300);
        Assert.assertFalse(d.isLocalRouterRunning());
    }

    @Test
    public void detectsSomethingListeningOnTheI2cpPort() throws IOException {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            LocalRouterDetector d = new LocalRouterDetector("127.0.0.1", port, 500);
            Assert.assertTrue("should detect the open socket", d.isLocalRouterRunning());
            Assert.assertEquals(port, d.getPort());
        }
    }
}
