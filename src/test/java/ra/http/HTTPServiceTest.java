package ra.http;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import ra.common.DLC;
import ra.common.Envelope;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Properties;
import java.util.logging.Logger;

import static ra.http.HTTPService.RA_HTTP_CLIENT_TRUST_ALL;
import static ra.http.HTTPService.RA_HTTP_SERVER_CONFIGS;

public class HTTPServiceTest {

    private static final Logger LOG = Logger.getLogger(HTTPServiceTest.class.getName());

    private static HTTPService service;
    private static MockProducer producer;
    private static Properties props;
    private static boolean ready = false;

    @BeforeClass
    public static void init() {
        LOG.info("Init...");
        props = new Properties();
        props.setProperty(RA_HTTP_CLIENT_TRUST_ALL, "true");
        props.setProperty(RA_HTTP_SERVER_CONFIGS, "HTTPServiceTest, API, localhost, 8099, ra.http.EnvelopeJSONDataHandler");

        producer = new MockProducer();
        service = new HTTPService(producer, null);

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
    public void httpClientTest() {
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
        service.sendOut(envelope);
        String html = new String((byte[]) envelope.getContent());
        Assert.assertTrue(html.contains("<title>Resolving Architecture</title>"));
    }

    @Test
    public void httpsClientTest() {
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
        service.sendOut(envelope);
        String html = new String((byte[]) envelope.getContent());
        Assert.assertTrue(html.contains("<title>Resolving Architecture</title>"));
    }

    @Test
    public void httpServerTest() {
        Envelope envelope = Envelope.documentFactory();
        try {
            envelope.setURL(new URL("http://localhost:8099/test"));
        } catch (MalformedURLException e) {
            LOG.severe(e.getLocalizedMessage());
            Assert.fail();
            return;
        }
        envelope.setHeader(Envelope.HEADER_CONTENT_TYPE, "text/html");
        envelope.setAction(Envelope.Action.GET);
        service.sendOut(envelope);
        String html = new String((byte[]) envelope.getContent());
        Assert.assertEquals("<html><body>HTTPServiceTest Available</body></html>", html);
    }

}
