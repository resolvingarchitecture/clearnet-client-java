package ra.http.client;

import ra.common.DLC;
import ra.common.Envelope;
import ra.common.messaging.MessageProducer;

import java.util.logging.Logger;

public class MockProducer implements MessageProducer {

    private static Logger LOG = Logger.getLogger(MockProducer.class.getName());

    @Override
    public boolean send(Envelope envelope) {
        LOG.info(envelope.toJSON());
        return true;
    }
}
