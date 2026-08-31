package ra.i2p;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.logging.Logger;

/**
 * Detects an I2P router already running on this host so {@link I2PService} can
 * attach to it (as an external I2CP client) instead of launching its own
 * embedded router - which avoids the port conflicts the README warns about and
 * the multi-minute reseed on every start.
 *
 * <p>On the JVM "local router" means: something is listening on the I2CP port
 * (default 7654) on loopback. This is the desktop/server analogue of what
 * {@code 1m5-android}'s {@code I2PLocal} does by binding to the I2P Android app's
 * router service.
 */
public final class LocalRouterDetector {

    private static final Logger LOG = Logger.getLogger(LocalRouterDetector.class.getName());

    /** Default I2CP host/port an I2P router listens on for client connections. */
    public static final String DEFAULT_I2CP_HOST = "127.0.0.1";
    public static final int DEFAULT_I2CP_PORT = 7654;

    private final String host;
    private final int port;
    private final int connectTimeoutMs;

    public LocalRouterDetector() {
        this(DEFAULT_I2CP_HOST, DEFAULT_I2CP_PORT, 750);
    }

    public LocalRouterDetector(String host, int port, int connectTimeoutMs) {
        this.host = host;
        this.port = port;
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    /** @return true if something is accepting connections on the I2CP host/port. */
    public boolean isLocalRouterRunning() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            LOG.info("Local I2P router detected on " + host + ":" + port);
            return true;
        } catch (IOException e) {
            LOG.fine("No local I2P router on " + host + ":" + port + " (" + e.getMessage() + ")");
            return false;
        }
    }
}
