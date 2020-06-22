package ra.http;

import io.onemfive.data.*;
import io.onemfive.data.content.Content;
import io.onemfive.util.DLC;
import io.onemfive.util.JSONParser;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.DefaultHandler;

import javax.servlet.MultipartConfigElement;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.Part;
import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Handles incoming requests by:
 *  - creating new Envelope from incoming deserialized JSON request
 *  - sending Envelope to the bus
 *  - blocking until a response is returned
 *  - serializing the Envelope into JSON
 *  - setting up Response letting it return
 *
 */
public class EnvelopeJSONDataHandler extends DefaultHandler implements io.onemfive.network.sensors.clearnet.AsynchronousEnvelopeHandler {

    private static Logger LOG = Logger.getLogger(io.onemfive.network.sensors.clearnet.EnvelopeJSONDataHandler.class.getName());

    private Map<Long,ClientHold> requests = new HashMap<>();
    private String id;
    private String serviceName;
    private String[] parameters;
    protected io.onemfive.network.sensors.clearnet.ClearnetSession session;
    private HttpSession activeSession;

    public EnvelopeJSONDataHandler() {}

    @Override
    public void setClearnetSession(io.onemfive.network.sensors.clearnet.ClearnetSession clearnetSession) {
        this.session = clearnetSession;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public void setParameters(String[] parameters) {
        this.parameters = parameters;
    }

    /**
     * Handles incoming requests by:
     *  - creating new Envelope from incoming deserialized JSON request
     *  - sending Envelope to the bus
     *  - blocking until a response is returned
     *  - serializing the Envelope into JSON
     *  - setting up Response letting it return
     * @param target the path sent after the ip address + port
     * @param baseRequest
     * @param request
     * @param response
     * @throws IOException
     * @throws ServletException
     */
    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        LOG.info("HTTP Handler called; target: "+target);
        if("/test".equals(target)) {
            response.setContentType("text/html");
            response.getWriter().print("<html><body>"+serviceName+" Available</body></html>");
            response.setStatus(200);
            baseRequest.setHandled(true);
            return;
        }
        int verifyStatus = verifyRequest(target, request);
        if(verifyStatus != 200) {
            response.setStatus(verifyStatus);
            baseRequest.setHandled(true);
            return;
        }

        long now = System.currentTimeMillis();
        if(!request.getSession().getId().equals(activeSession.getId())) {
            activeSession = request.getSession();
            request.getSession().setMaxInactiveInterval(io.onemfive.network.sensors.clearnet.ClearnetSession.SESSION_INACTIVITY_INTERVAL);
            session.setSessionId(activeSession.getId());
        } else if(session.getLastRequestTime() + now > io.onemfive.network.sensors.clearnet.ClearnetSession.SESSION_INACTIVITY_INTERVAL * 1000){
            request.getSession().invalidate();
            activeSession = request.getSession(true);
            request.getSession().setMaxInactiveInterval(io.onemfive.network.sensors.clearnet.ClearnetSession.SESSION_INACTIVITY_INTERVAL);
            session.setSessionId(activeSession.getId());
        }

        session.setLastRequestTime(now);

        Envelope envelope = parseEnvelope(target, request, session.sessionId);
        ClientHold clientHold = new ClientHold(target, baseRequest, request, response, envelope);
        requests.put(envelope.getId(), clientHold);

        route(envelope); // asynchronous call upon; returns upon reaching Message Channel's queue in Service Bus

        if(DLC.getErrorMessages(envelope).size() > 0) {
            // Just 500 for now
            LOG.warning("Returning HTTP 500...");
            response.setStatus(500);
            baseRequest.setHandled(true);
            requests.remove(envelope.getId());
        } else {
            // Hold Thread until response or 30 seconds
//            LOG.info("Holding HTTP Request for up to 30 seconds waiting for internal asynch response...");
            clientHold.hold(30 * 1000); // hold for 30 seconds or until interrupted
        }
    }

    protected void route(Envelope e) {
        io.onemfive.network.Request req = new io.onemfive.network.Request();
        req.setEnvelope(e);
        session.sendIn(req);
    }

    public void reply(Envelope e) {
        ClientHold hold = requests.get(e.getId());
        HttpServletResponse response = hold.getResponse();
        LOG.info("Updating session status from response...");
        String sessionId = (String)e.getHeader(io.onemfive.network.sensors.clearnet.ClearnetSession.class.getName());
        if(activeSession==null) {
            // session expired before response received so kill
            LOG.warning("Expired session before response received: sessionId="+sessionId);
            respond("{httpErrorCode=401}", "application/json", response, 401);
        } else {
            LOG.info("Active session found");
            DID eDID = e.getDID();
            LOG.info("DID in header: "+eDID);
            if(!session.getAuthenticated() && eDID.getAuthenticated()) {
                LOG.info("Updating active session to authenticated.");
                session.setAuthenticated(true);
            }
            respond(unpackEnvelopeContent(e), "application/json", response, 200);
        }
        hold.baseRequest.setHandled(true);
        LOG.info("Waking sleeping request thread to return response to caller...");
        hold.wake(); // Interrupt sleep to allow thread to return
        LOG.info("Unwinded request call with response.");
    }

    protected int verifyRequest(String target, HttpServletRequest request) {

        return 200;
    }

    protected Envelope parseEnvelope(String target, HttpServletRequest request, String sessionId) {
//        LOG.info("Parsing request into Envelope...");
        String json = request.getParameter("env");
        if(json==null) {
            LOG.warning("No Envelope in env parameter.");
            return null;
        }
        Envelope e = new Envelope();
        e.fromJSON(json);

        // Must set id in header for asynchronous support
        e.setHeader(io.onemfive.network.sensors.clearnet.ClearnetSession.HANDLER_ID, id);
        e.setHeader(io.onemfive.network.sensors.clearnet.ClearnetSession.SESSION_ID, sessionId);

        // Set path
        e.setCommandPath(target.startsWith("/")?target.substring(1):target); // strip leading slash if present
        try {
            // This is required to ensure the SensorManager knows to return the reply to the ClearnetServerSensor (ends with .json)
            URL url = new URL("http://127.0.0.1"+target+".json");
            e.setURL(url);
        } catch (MalformedURLException e1) {
            LOG.warning(e1.getLocalizedMessage());
        }

        // Populate method
        String method = request.getMethod();
//        LOG.info("Incoming method: "+method);
        if(method != null) {
            switch (method.toUpperCase()) {
                case "GET": e.setAction(Envelope.Action.GET);break;
                case "POST": e.setAction(Envelope.Action.POST);break;
                case "PUT": e.setAction(Envelope.Action.PUT);break;
                case "DELETE": e.setAction(Envelope.Action.DELETE);break;
                default: e.setAction(Envelope.Action.GET);
            }
        } else {
            e.setAction(Envelope.Action.GET);
        }

        // Populate headers
        Enumeration<String> headerNames = request.getHeaderNames();
        while(headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            Enumeration<String> headerValues = request.getHeaders(headerName);
            boolean first = true;
            int i = 2;
            while(headerValues.hasMoreElements()){
                String headerValue = headerValues.nextElement();
                if(first) {
                    e.setHeader(headerName, headerValue);
                    first = false;
                } else {
                    e.setHeader(headerName + Integer.toString(i++), headerValue);
                }
//                LOG.info("Incoming header:value="+headerName+":"+headerValue);
            }
        }

        // Get file content if sent
        if(e.getContentType() != null && e.getContentType().startsWith("multipart/form-data")) {
        	request.setAttribute(Request.__MULTIPART_CONFIG_ELEMENT, new MultipartConfigElement(""));
            try {
                Collection<Part> parts = request.getParts();
                String contentType;
                String name;
                String fileName;
                long size = 0;
                InputStream is;
                ByteArrayOutputStream b;
                int k = 0;
                for (Part part : parts) {
                    String msg = "Downloading... {";
                    name = part.getName();
                    msg += "\n\tparamName="+name;
                    fileName = part.getSubmittedFileName();
                    msg += "\n\tfileName="+fileName;
                    contentType = part.getContentType();
                    msg += "\n\tcontentType="+contentType;
                    size = part.getSize();
                    msg += "\n\tsize="+size+"\n}";
                    LOG.info(msg);
                    if(size > 1000000) {
                        // 1Mb
                        LOG.warning("Downloading of file with size="+size+" prevented. Max size is 1Mb.");
                        return e;
                    }
                    is = part.getInputStream();
                    if (is != null) {
                        b = new ByteArrayOutputStream();
                        int nRead;
                        byte[] bucket = new byte[16384];
                        while ((nRead = is.read(bucket, 0, bucket.length)) != -1) {
                            b.write(bucket, 0, nRead);
                        }
                        Content content = Content.buildContent(b.toByteArray(), contentType, fileName, true, true);
                        content.setSize(size);
                        if (k == 0) {
                            Map<String, Object> d = ((DocumentMessage) e.getMessage()).data.get(k++);
                            d.put(Envelope.HEADER_CONTENT_TYPE, contentType);
                            d.put(DLC.CONTENT, content);
                        } else {
                            Map<String, Object> d = new HashMap<>();
                            d.put(Envelope.HEADER_CONTENT_TYPE, contentType);
                            d.put(DLC.CONTENT, content);
                            ((DocumentMessage) e.getMessage()).data.add(d);
                        }
                    }
                }
            } catch (Exception e1) {
                LOG.warning(e1.getLocalizedMessage());
            }
        }

        //Get post formData params
        String postFormBody = getPostRequestFormData(request);
        if(!postFormBody.isEmpty()){
            Map<String, Object> bodyMap = (Map<String, Object>) JSONParser.parse(postFormBody);
            DLC.addData(Map.class, bodyMap, e);
        }

        // Get query parameters if present
        String query = request.getQueryString();
        if(query!=null) {
//            LOG.info("Incoming query: "+query);
            Map<String, String> queryMap = new HashMap<>();
            String[] nvps = query.split("&");
            for (String nvpStr : nvps) {
                String[] nvp = nvpStr.split("=");
                queryMap.put(nvp[0], nvp[1]);
            }
            DLC.addData(Map.class, queryMap, e);
        }
        e.setExternal(true);

        // Get post parameters if present and place as content
        Map<String, String[]> m = request.getParameterMap();
        if(m != null && !m.isEmpty()) {
            DLC.addContent(m, e);
        }

        return e;
    }

    protected String unpackEnvelopeContent(Envelope e) {
//        LOG.info("Unpacking Content Map to JSON");
        String json = JSONParser.toString(((JSONSerializable)DLC.getContent(e)).toMap());
        return json;
    }

    public String getPostRequestFormData(HttpServletRequest request)  {
        StringBuilder formData = new StringBuilder();
        BufferedReader bufferedReader = null;
        try {
            InputStream inputStream = request.getInputStream();
            if (inputStream != null) {
                bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
                char[] charBuffer = new char[128];
                int bytesRead = -1;
                while ((bytesRead = bufferedReader.read(charBuffer)) > 0) {
                    formData.append(charBuffer, 0, bytesRead);
                }
            }
        } catch (IOException ex) {
            LOG.warning(ex.getLocalizedMessage());
        } finally {
            if (bufferedReader != null) {
                try {
                    bufferedReader.close();
                } catch (IOException ex) {
                    LOG.warning(ex.getLocalizedMessage());
                }
            }
        }

        return formData.toString();
    }

    protected void respond(String body, String contentType, HttpServletResponse response, int code) {
//        LOG.info("Returning response...");
        response.setContentType(contentType);
        try {
            response.getWriter().print(body);
            response.setStatus(code);
        } catch (IOException ex) {
            LOG.warning(ex.getLocalizedMessage());
            response.setStatus(500);
        }
    }

    private class ClientHold {
        private Thread thread;
        private String target;
        private Request baseRequest;
        private HttpServletRequest request;
        private HttpServletResponse response;
        private Envelope envelope;

        private ClientHold(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response, Envelope envelope) {
            this.target = target;
            this.baseRequest = baseRequest;
            this.request = request;
            this.response = response;
            this.envelope = envelope;
        }

        private void hold(long waitTimeMs) {
            thread = Thread.currentThread();
            try {
                Thread.sleep(waitTimeMs);
            } catch (InterruptedException e) {
                requests.remove(envelope.getId());
            }
        }

        private void wake() {
            thread.interrupt();
        }

        private String getTarget() {
            return target;
        }

        private Request getBaseRequest() {
            return baseRequest;
        }

        private HttpServletRequest getRequest() {
            return request;
        }

        private HttpServletResponse getResponse() {
            return response;
        }

        private Envelope getEnvelope() {
            return envelope;
        }
    }

}
