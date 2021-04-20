package ra.http;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.session.*;
import ra.common.Client;
import ra.common.DLC;
import ra.common.Envelope;
import ra.common.content.Content;
import ra.common.identity.DID;
import ra.common.messaging.DocumentMessage;
import ra.util.JSONParser;
import ra.util.RandomUtil;

import javax.servlet.MultipartConfigElement;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import java.io.*;
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
 */
public class EnvelopeJSONDataHandler extends DefaultHandler implements Client, EnvelopeHandler {

    private static Logger LOG = Logger.getLogger(EnvelopeJSONDataHandler.class.getName());

    private Map<String,ClientHold> requests = new HashMap<>();
    private String id;
    private String serverName;
    private String[] parameters;
    protected HTTPService service;

    public EnvelopeJSONDataHandler() {
        id = RandomUtil.randomAlphanumeric(16);
    }

    @Override
    public void setService(HTTPService service) {
        this.service = service;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
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
            response.getWriter().print("<html><body>"+serverName+" Available</body></html>");
            response.setStatus(200);
            baseRequest.setHandled(true);
            return;
        }

        Session session = (Session)request.getSession(true);
        Envelope envelope = parseEnvelope(target, request, session.getId());
        if(DLC.markerPresent("op", envelope)) {
            // Set response and allow to unwind
            LOG.info("Received NetOp, replying with {op=200}...");
            response.setStatus(200);
            DLC.addContent("{op=200}", envelope);
        } else {
            // Set up hold to support async back-end
            LOG.info("Received normal request; setting up client hold and forwarding to bus...");
            ClientHold clientHold = new ClientHold(target, baseRequest, request, response, envelope);
            requests.put(envelope.getId(), clientHold);
            service.send(envelope, this);

            if (DLC.getErrorMessages(envelope).size() > 0) {
                // Just 500 for now
                LOG.warning("Returning HTTP 500...");
                response.setStatus(500);
                baseRequest.setHandled(true);
                requests.remove(envelope.getId());
            } else {
                // Hold Thread until response or 30 seconds
                LOG.info("Holding HTTP Request for up to 30 seconds waiting for internal asynch response...");
                clientHold.hold(30 * 1000); // hold for 30 seconds or until interrupted
            }
        }
    }

    public void reply(Envelope e) {
        ClientHold hold = requests.get(e.getId());
        HttpServletResponse response = hold.getResponse();
        DID eDID = e.getDID();
        LOG.info("DID in header: "+eDID);
        respond(unpackEnvelopeContent(e), "application/json", response, 200);
        hold.baseRequest.setHandled(true);
        LOG.info("Waking sleeping request thread to return response to caller...");
        hold.wake(); // Interrupt sleep to allow thread to return
        LOG.info("Unwinded request call with response.");
    }

    protected Envelope parseEnvelope(String target, HttpServletRequest request, String sessionId) {
        LOG.info("Parsing request into Envelope with target: "+target);

        Envelope e = Envelope.documentFactory();

        // Must set id in header for asynchronous support
        e.setHeader(HTTPService.HANDLER_ID, id);
        e.setHeader(HTTPService.SESSION_ID, sessionId);

        // Populate method
        String method = request.getMethod();
        LOG.info("Incoming method: "+method);
        if(method == null) {
            e.setAction(Envelope.Action.GET);
        } else {
            switch (method.toUpperCase()) {
                case "POST": e.setAction(Envelope.Action.POST);break;
                case "PUT": e.setAction(Envelope.Action.PUT);break;
                case "DELETE": e.setAction(Envelope.Action.DELETE);break;
                default: e.setAction(Envelope.Action.GET);
            }
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
                    e.setHeader(headerName + i++, headerValue);
                }
//                LOG.info("Incoming header:value="+headerName+":"+headerValue);
            }
        }

        // Get file content if sent
        if(e.getContentType() != null && e.getContentType().startsWith("multipart/form-data")) {
        	request.setAttribute(Request.MULTIPART_CONFIG_ELEMENT, new MultipartConfigElement(""));
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
                        Content content = Content.buildContent(b.toByteArray(), contentType, fileName);
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
        LOG.info("PostFormBody: "+postFormBody);
        if(!postFormBody.isEmpty() && postFormBody.startsWith("{")){
            // strip Envelope
            Map<String, Object> m = (Map) JSONParser.parse(postFormBody);
            if (m != null) {
                m = (Map) m.get("envelope");
                if (m != null)
                    e.fromMap(m);
            }
        }

        // Get query parameters if present
        String query = request.getQueryString();
        if(query!=null) {
            LOG.info("Incoming query: "+query);
            Map<String, String> queryMap = new HashMap<>();
            String[] nvps = query.split("&");
            for (String nvpStr : nvps) {
                String[] nvp = nvpStr.split("=");
                queryMap.put(nvp[0], nvp[1]);
            }
            DLC.addData(Map.class, queryMap, e);
        }

        // Get post parameters if present and place as content
        Map<String, String[]> m = request.getParameterMap();
        if(m != null && !m.isEmpty()) {
            DLC.addContent(m, e);
        }

        return e;
    }

    protected String unpackEnvelopeContent(Envelope e) {
        if(DLC.getContent(e) == null)
            return "{200}";
        else
            return ((Content)DLC.getContent(e)).toJSON();
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

    @Override
    public void invalidateSessions() {

    }

    protected class ClientHold {
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
            thread = Thread.currentThread();
        }

        private void hold(long waitTimeMs) {
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
