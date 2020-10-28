package ra.i2p;

import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.data.Destination;
import ra.common.network.BaseClientSession;

public abstract class I2PSessionBase extends BaseClientSession {

    protected I2PSession i2pSession;

    public Destination lookupDest(String address) {
        Destination destination = null;
        try {
            destination = i2pSession.lookupDest(address);
        } catch (I2PSessionException e) {
            e.printStackTrace();
        }
        return destination;
    }
}
