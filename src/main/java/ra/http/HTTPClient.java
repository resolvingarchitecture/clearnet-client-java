package ra.http;

import ra.common.Network;

import java.io.File;
import java.io.IOException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Clearnet access acting as a Client and Server
 */
public class HTTPClient extends BaseSensor {

    private static final Logger LOG = Logger.getLogger(io.onemfive.network.sensors.clearnet.ClearnetSensor.class.getName());

    /**
     * Configuration of Sessions in the form:
     *      name, port, launch on start, concrete implementation of io.onemfive.network.sensors.clearnet.AsynchronousEnvelopeHandler, run websocket, relative resource directory|n,...}
     */
    public static final String CLEARNET_SESSIONS_CONFIG = "settings.network.clearnet.sessionConfigs";
    public static final String CLEARNET_SESSION_CONFIG = "settings.network.clearnet.sessionConfig";

    private boolean isTest = false;
    private boolean clientsEnabled = false;
    private boolean serversEnabled = false;

    public HTTPClient() {
        super(Network.HTTPS);
    }

    public HTTPClient(Network network) {
        super(network);
    }

    public HTTPClient(SensorManager sensorManager) {
        super(sensorManager, Network.HTTPS);
    }

    public HTTPClient(SensorManager sensorManager, Network network) {
        super(sensorManager, network);
    }

    @Override
    public String[] getOperationEndsWith() {
        return new String[]{".html",".htm",".do",".json"};
    }

    @Override
    public String[] getURLBeginsWith() {
        return new String[]{"http","https"};
    }

    @Override
    public String[] getURLEndsWith() {
        return new String[]{".html",".htm",".do",".json"};
    }

    @Override
    public SensorSession establishSession(String spec, Boolean autoConnect) {
        Properties props;
        if(sessions.get(spec)==null) {
            SensorSession sensorSession = new HTTPClientSession(this);
            props = new Properties();
            props.setProperty(CLEARNET_SESSION_CONFIG, spec);
            sensorSession.init(props);
            sensorSession.open("127.0.0.1");
            if (autoConnect) {
                sensorSession.connect();
            }
            sessions.put(spec, sensorSession);
        }
        return sessions.get(spec);
    }

    @Override
    public void updateState(NetworkState networkState) {
        LOG.warning("Not implemented.");
    }

    @Override
    public boolean sendOut(NetworkPacket packet) {
        LOG.info("Send Clearnet Message Out Packet received...");
        SensorSession sensorSession = establishSession(null, true);
        return sensorSession.send(packet);
    }

    @Override
    public boolean start(Properties p) {
        LOG.info("Starting...");
        properties = p;
        updateStatus(SensorStatus.INITIALIZING);
        String sensorsDirStr = properties.getProperty("1m5.dir.sensors");
        if (sensorsDirStr == null) {
            LOG.warning("1m5.dir.sensors property is null. Please set prior to instantiating Clearnet Client Sensor.");
            return false;
        }
        try {
            File sensorDir = new File(new File(sensorsDirStr), "clearnet");
            if (!sensorDir.exists() && !sensorDir.mkdir()) {
                LOG.warning("Unable to create Clearnet Sensor directory.");
                return false;
            } else {
                properties.put("1m5.dir.sensors.clearnet", sensorDir.getCanonicalPath());
            }
        } catch (IOException e) {
            LOG.warning("IOException caught while building Clearnet sensor directory: \n" + e.getLocalizedMessage());
            return false;
        }
        String sessionsConfig = properties.getProperty(CLEARNET_SESSIONS_CONFIG);
        LOG.info("Building sessions from configuration: " + sessionsConfig);
        String[] sessionsSpecs = sessionsConfig.split(":");
        LOG.info("Number of sessions to start: " + sessionsSpecs.length);

        updateStatus(SensorStatus.STARTING);

        for(String spec : sessionsSpecs) {
            establishSession(spec, true);
        }
        return true;
    }

    @Override
    public boolean pause() {
        LOG.warning("Pausing not supported.");
        return false;
    }

    @Override
    public boolean unpause() {
        LOG.warning("Pausing not supported.");
        return false;
    }

    @Override
    public boolean restart() {
        LOG.info("Restarting...");
        for(SensorSession session : sessions.values()) {
            String address = session.getAddress();
            if(!session.close() || !session.open(address))
                return false;
        }
        LOG.info("Restarted.");
        return true;
    }

    @Override
    public boolean shutdown() {
        LOG.info("Shutting down...");
        for(SensorSession session : sessions.values()) {
            session.close();
        }
        LOG.info("Shutdown.");
        return true;
    }

    @Override
    public boolean gracefulShutdown() {
        return shutdown();
    }

}
