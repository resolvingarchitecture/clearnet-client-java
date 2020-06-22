package ra.http;


import ra.common.Envelope;

/**
 * TODO: Add Description
 *
 */
public interface AsynchronousEnvelopeHandler extends Handler {
    void setSession(HTTPClientSession clientSession);
    void setServiceName(String serviceName);
    void setParameters(String[] parameters);
    void reply(Envelope envelope);
}
