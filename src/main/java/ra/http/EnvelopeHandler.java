package ra.http;

import org.eclipse.jetty.server.Handler;

/**
 *
 */
public interface EnvelopeHandler extends Handler {
    void setService(HTTPService service);
    void setServerName(String serverName);
    void setParameters(String[] parameters);
    void invalidateSessions();
}
