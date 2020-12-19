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
    public boolean send(Envelope e) {
        LOG.info(e.toJSON());
        e.addContent(contentValue);
        return true;
    }

    @Override
    public boolean send(Envelope e, Client client) {
        LOG.info(e.toJSON());
        e.addContent(contentValue);
        return true;
    }

    @Override
    public boolean deadLetter(Envelope e) {
        LOG.info(e.toJSON());
        e.addContent(contentValue);
        return true;
    }
}
