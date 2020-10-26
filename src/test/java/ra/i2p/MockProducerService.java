package ra.i2p;

import ra.common.Client;
import ra.common.DLC;
import ra.common.Envelope;
import ra.common.messaging.MessageProducer;

import java.util.logging.Logger;

public class MockProducerService implements MessageProducer {

    private static Logger LOG = Logger.getLogger(MockProducerService.class.getName());

    public String contentValue = "TestSat";

    @Override
    public boolean send(Envelope envelope) {
        LOG.info(envelope.toJSON());
        DLC.addContent(contentValue, envelope);
        return true;
    }

    @Override
    public boolean send(Envelope envelope, Client client) {
        LOG.info(envelope.toJSON());
        DLC.addContent(contentValue, envelope);
        return true;
    }
}
