package ra.i2p;

import ra.common.Client;
import ra.common.Envelope;
import ra.common.messaging.MessageProducer;

import java.util.logging.Logger;

public class MockProducerClient implements MessageProducer {

    private static Logger LOG = Logger.getLogger(MockProducerClient.class.getName());

    @Override
    public boolean send(Envelope envelope) {
        LOG.info(envelope.toJSON());
        return true;
    }

    @Override
    public boolean send(Envelope envelope, Client client) {
        LOG.info(envelope.toJSON());
        return true;
    }

    @Override
    public boolean deadLetter(Envelope envelope) {
        LOG.info(envelope.toJSON());
        return true;
    }
}
