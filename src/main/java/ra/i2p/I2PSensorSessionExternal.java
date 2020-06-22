package ra.i2p;

import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionMuxedListener;

import java.util.logging.Logger;

public class I2PSensorSessionExternal extends BaseSession implements I2PSessionMuxedListener {

    private static final Logger LOG = Logger.getLogger(io.onemfive.network.sensors.i2p.I2PSensorSessionExternal.class.getName());

    private boolean connected = false;
    private String address;

    public I2PSensorSessionExternal(I2P sensor) {
        super(sensor);
    }

    public String getAddress() {
        return address;
    }

    @Override
    public boolean open(String address) {
        this.address = address;
        LOG.warning("Not yet implemented.");
        return false;
    }

    @Override
    public boolean connect() {
        LOG.warning("Not yet implemented.");
        return false;
    }

    @Override
    public boolean disconnect() {
        LOG.warning("Not yet implemented.");
        return false;
    }

    @Override
    public boolean isConnected() {
        LOG.warning("Not yet implemented.");
        return false;
    }

    @Override
    public boolean close() {
        LOG.warning("Not yet implemented.");
        return false;
    }

    @Override
    public Boolean send(NetworkPacket packet) {
        LOG.warning("Not yet implemented.");
        return false;
    }

    @Override
    public boolean send(NetworkRequestOp requestOp) {
        LOG.warning("Not yet implemented.");
        return false;
    }

    @Override
    public boolean notify(NetworkNotifyOp notifyOp) {
        LOG.warning("Not yet implemented.");
        return false;
    }

    @Override
    public void messageAvailable(I2PSession i2PSession, int i, long l) {
        LOG.warning("Not yet implemented.");
    }

    @Override
    public void messageAvailable(I2PSession i2PSession, int i, long l, int i1, int i2, int i3) {
        LOG.warning("Not yet implemented.");
    }

    @Override
    public void reportAbuse(I2PSession i2PSession, int i) {
        LOG.warning("Not yet implemented.");
    }

    @Override
    public void disconnected(I2PSession i2PSession) {
        LOG.warning("Not yet implemented.");
    }

    @Override
    public void errorOccurred(I2PSession i2PSession, String s, Throwable throwable) {
        LOG.warning("Not yet implemented.");
    }
}
