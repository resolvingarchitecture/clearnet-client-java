package ra.http;

import io.onemfive.data.Envelope;
import io.onemfive.network.Request;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Handles incoming web requests from browsers by receiving Envelopes in JSON,
 * converting them to Java Envelopes, sending them to the Bus, receiving
 * Envelopes from the Bus, serializing them into JSON, and sending them to the browser.
 *
 * All routes and data must be placed appropriately within the Envelope for it to route properly.
 *
 * Jetty sets Session through onWebSocketConnect().
 *
 * Feel free to extend overriding onWebSocketText() and pushEnvelope().
 *
 */
public class EnvelopeWebSocket extends WebSocketAdapter {

    private static Logger LOG = Logger.getLogger(io.onemfive.network.sensors.clearnet.EnvelopeWebSocket.class.getName());

    protected HTTPClientSession clearnetSession;
    protected Session session;

    public EnvelopeWebSocket() {}

    public EnvelopeWebSocket(HTTPClientSession clearnetSession) {
        this.clearnetSession = clearnetSession;
    }

    public void setClearnetSession(HTTPClientSession clearnetSession) {
        this.clearnetSession = clearnetSession;
    }

    @Override
    public void onWebSocketConnect(Session session) {
        super.onWebSocketConnect(session);
        LOG.info("+++ WebSocket Connect...");
        this.session = session;
        LOG.info("Host: "+session.getRemoteAddress().getAddress().getCanonicalHostName());
    }

    @Override
    public void onWebSocketText(String message) {
        LOG.info("WebSocket Text received: "+message);
        if(message != null && !message.equals("keep-alive")) {
            Envelope e = Envelope.documentFactory();
            e.fromJSON(message);
            LOG.info("Sending Envelope received to bus...\n\t"+e);
            Request request = new Request();
            request.setEnvelope(e);
            // Send to bus
            clearnetSession.sendIn(request);
        }
    }

    public void pushEnvelope(Envelope e) {
        LOG.info("Received Envelope to send to browser:\n\t" + e);
        if (session == null) {
            LOG.warning("Jetty WebSocket session not yet established. Unable to send message.");
            return;
        }
        try {
            RemoteEndpoint endpoint = session.getRemote();
            if (endpoint == null) {
                LOG.warning("No RemoteEndpoint found for current Jetty WebSocket session.");
            } else {
                LOG.info("Sending Envelope as JSON text to browser...");
                endpoint.sendString(e.toJSON());
                LOG.info("Envelope as JSON text sent to browser.");
            }
        } catch (IOException ex) {
            LOG.warning(ex.getLocalizedMessage());
        }
    }

}
