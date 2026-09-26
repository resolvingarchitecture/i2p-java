package ra.i2p;

import org.junit.Test;
import ra.common.Envelope;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * {@link I2PServiceSession#advanceToNextLocalHop(Envelope)} - the inbound-side
 * fix so a hop the sender pushed underneath its own local I2P-dispatch hop
 * (the receiver's actual intent, e.g. a specific business operation) is what
 * this node acts on, not the stale outer hop. See that method's own javadoc.
 */
public class I2PServiceSessionRedispatchTest {

    @Test
    public void advancesToTheRemainingHopWhenOneExists() {
        Envelope e = Envelope.documentFactory();
        e.addRoute("SomeBusinessService", "SOME_REMOTE_OP"); // the sender's real intent for us
        e.addExternalRoute("I2P", "SEND");                    // the sender's own local dispatch hop
        e.ratchet(); // simulates deserializing an envelope mid-transit, route = I2P/SEND

        I2PServiceSession.advanceToNextLocalHop(e);

        assertEquals("SomeBusinessService", e.getRoute().getService());
        assertEquals("SOME_REMOTE_OP", e.getRoute().getOperation());
    }

    @Test
    public void noOpWhenNothingRemains() {
        Envelope e = Envelope.documentFactory();
        e.addRoute("I2P", "SEND");
        e.ratchet(); // route = I2P/SEND, nothing else on the slip

        I2PServiceSession.advanceToNextLocalHop(e);

        assertEquals("route is unchanged - there was nothing to advance to", "I2P", e.getRoute().getService());
        assertEquals("SEND", e.getRoute().getOperation());
    }

    @Test
    public void noOpWhenRouteWasNeverSet() {
        Envelope e = Envelope.documentFactory();
        I2PServiceSession.advanceToNextLocalHop(e);
        assertNull(e.getRoute());
    }
}