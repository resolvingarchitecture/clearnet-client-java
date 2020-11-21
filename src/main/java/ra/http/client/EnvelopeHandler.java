package ra.http.client;

import org.eclipse.jetty.server.Handler;

/**
 *
 */
public interface EnvelopeHandler extends Handler {
    void setService(HTTPClientService service);
    void setServerName(String serverName);
    void setParameters(String[] parameters);
    void invalidateSessions();
}
