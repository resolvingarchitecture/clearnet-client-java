package ra.http.client;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * Single-Page Application Handler
 *
 */
public class SPAHandler extends AbstractHandler {

    private static Logger LOG = Logger.getLogger(io.onemfive.network.sensors.clearnet.SPAHandler.class.getName());
    private boolean firstRequest = true;

    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        //If request not coming for DataServiceHandler, redirect all requests to the "/". Bc UI is already handling routes.
        String requestUri = request.getRequestURI();
    //    if(!requestUri.startsWith("/data")){
        if(requestUri.equals("/login") ||  requestUri.equals("/logout") ){
            requestUri = "/" ;
            String uri = request.getScheme() + "://" +
                    request.getServerName() +
                    ":" + request.getLocalPort() +
                    requestUri +
                    (request.getQueryString() != null ? "?" + request.getQueryString() : "");
            response.sendRedirect(uri);
        }
    }

    /*
    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        // On 404 page we need to show index.html and let JS router do the work, otherwise show error page
        String redirectRoute = "/index.html";
        LOG.info("HTTP Status: "+response.getStatus());
        if ((response.getStatus() == HttpServletResponse.SC_NOT_FOUND)) {
            LOG.info("404 caught; forwarding to /index.html...");
            RequestDispatcher dispatcher = request.getRequestDispatcher(redirectRoute);
            if (dispatcher != null) {
                try {
                    // reset response
                    response.reset();
                    // On second 404 request we need to show original 404 page, otherwise will be redirect loop
                    firstRequest = false;
                    LOG.info("Initiate forward...");
                    dispatcher.forward(request, response);
                } catch (ServletException e) {
                    LOG.warning("ServletException caught while forwarding...");
                    super.handle(target, baseRequest, request, response);
                }
            } else {
                LOG.info("Unable to find redirect route.");
            }
//        } else if ((response.getStatus() == HttpServletResponse.SC_NOT_FOUND)) {
//            LOG.severe("Can not find internal redirect route " + redirectRoute + " on 404 error. Will show system 404 page");
        } else {
            LOG.info("Letting ErrorHandler handle this.");
            super.handle(target, baseRequest, request, response);
        }
    }

    */

//    @Override
//    protected void writeErrorPageBody(HttpServletRequest request, Writer writer, int code, String message, boolean showStacks) throws IOException
//    {
//        writeErrorPageMessage(request, writer, code, message, request.getRequestURI());
//    }
//
//    @Override
//    protected void writeErrorPageMessage(HttpServletRequest request, Writer writer, int code, String message, String uri)
//            throws IOException
//    {
//        String statusMessage = Integer.toString(code) + " " + message;
//        LOG.severe("Problem accessing " + uri + ". " + statusMessage);
//        writer.write(statusMessage);
//    }

}
