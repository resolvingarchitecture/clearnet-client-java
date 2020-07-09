package ra.http.client;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import ra.common.DLC;
import ra.common.Envelope;
import ra.common.network.Request;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Properties;
import java.util.logging.Logger;

import static ra.http.client.HTTPClientService.RA_HTTP_CLIENT_TRUST_ALL;

public class HTTPClientServiceTest {

    private static final Logger LOG = Logger.getLogger(HTTPClientServiceTest.class.getName());

    private static HTTPClientService service;
    private static MockProducer producer;
    private static Properties props;
    private static boolean ready = false;

    @BeforeClass
    public static void init() {
        LOG.info("Init...");
        props = new Properties();
        props.setProperty(RA_HTTP_CLIENT_TRUST_ALL, "true");

        producer = new MockProducer();

        service = new HTTPClientService(producer, null);

        ready = service.start(props);
    }

    @AfterClass
    public static void tearDown() {
        LOG.info("Teardown...");
        service.gracefulShutdown();
    }

    @Test
    public void verifyInitializedTest() {
        Assert.assertTrue(ready);
    }

    @Test
    public void httpTest() {
        Envelope envelope = Envelope.documentFactory();
        try {
            envelope.setURL(new URL("http://resolvingarchitecture.io"));
        } catch (MalformedURLException e) {
            LOG.severe(e.getLocalizedMessage());
            Assert.fail();
            return;
        }
        envelope.setHeader(Envelope.HEADER_CONTENT_TYPE, "text/html");
        envelope.setAction(Envelope.Action.GET);
        Request request = new Request();
        request.setEnvelope(envelope);
        service.send(request);
        String html = new String((byte[]) DLC.getContent(envelope));
        Assert.assertTrue(html.contains("<title>Resolving Architecture</title>"));
    }

    @Test
    public void httpsTest() {
        Envelope envelope = Envelope.documentFactory();
        try {
            envelope.setURL(new URL("https://resolvingarchitecture.io"));
        } catch (MalformedURLException e) {
            LOG.severe(e.getLocalizedMessage());
            Assert.fail();
            return;
        }
        envelope.setHeader(Envelope.HEADER_CONTENT_TYPE, "text/html");
        envelope.setAction(Envelope.Action.GET);
        Request request = new Request();
        request.setEnvelope(envelope);
        service.send(request);
        String html = new String((byte[]) DLC.getContent(envelope));
        Assert.assertTrue(html.contains("<title>Resolving Architecture</title>"));
    }
}
