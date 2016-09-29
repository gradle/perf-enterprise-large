package org.gradle.mavenrepo;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.NCSARequestLog;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;

public class JettyMavenRepo {
    public static void main(String[] args) throws Exception {
        Server server = new Server(8000);
        configureAccessLogs(server);
        server.setStopAtShutdown(false);
        ResourceHandler resource_handler = new ResourceHandler();
        resource_handler.setDirectoriesListed(true);
        resource_handler.setWelcomeFiles(new String[]{"index.html"});

        resource_handler.setResourceBase(args[0]);

        Handler shutdownHandler = new AbstractHandler() {
            @Override
            public void handle(final String target, final Request baseRequest, final HttpServletRequest request, final HttpServletResponse response) throws IOException, ServletException {
                if ("/stop".equals(target)) {
                    new Thread() {
                        @Override
                        public void run() {

                            try {
                                server.stop();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                            server.destroy();
                        }
                    }.start();
                }
            }
        };

        HandlerList handlers = new HandlerList();
        handlers.setHandlers(new Handler[]{shutdownHandler, resource_handler, new DefaultHandler()});
        server.setHandler(handlers);

        server.start();
        while (!server.isStarted()) {
            Thread.sleep(10);
        }
        System.out.println("Serving Repository from " + new File(args[0]).getAbsolutePath());
        System.out.println("On http://localhost:8000/");
        server.join();

    }

    private static void configureAccessLogs(Server server) {
        String accessLogPath = System.getenv("MAVEN_SERVER_ACCESS_LOG");
        if (accessLogPath != null) {
            NCSARequestLog requestLog = new NCSARequestLog(accessLogPath);
            requestLog.setAppend(true);
            requestLog.setExtended(true);
            requestLog.setLogLatency(true);
            server.setRequestLog(requestLog);
        }
    }
}

