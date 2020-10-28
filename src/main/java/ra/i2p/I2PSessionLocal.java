package ra.i2p;


import ra.common.Envelope;

class I2PSessionLocal extends I2PSessionBase {

    private I2PService service;

    public I2PSessionLocal(I2PService service) {
        this.service = service;
    }

    @Override
    public boolean open(String address) {
        return false;
    }

    @Override
    public boolean connect() {
        return false;
    }

    @Override
    public boolean disconnect() {
        return false;
    }

    @Override
    public boolean isConnected() {
        return false;
    }

    @Override
    public boolean close() {
        return false;
    }

    @Override
    public Boolean send(Envelope envelope) {
        return null;
    }

}
