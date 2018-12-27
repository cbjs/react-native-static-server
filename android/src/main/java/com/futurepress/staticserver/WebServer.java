package com.futurepress.staticserver;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import fi.iki.elonen.NanoFileUpload;
import fi.iki.elonen.SimpleWebServer;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;

public class WebServer extends SimpleWebServer
{
    private Handler handler;
    private NanoFileUpload uploader;
    private Map<String, Condition> conditions;
    private Map<String, String> responses;
    private Lock lock;

    public interface Handler {
        void handle(String id, String uri, Map<String, List<String>> params, List<FileItem> files);

        int timeoutInMillis();

        InputStream assets(String uri);

        boolean tryAssets();
    }

    public WebServer(String localAddr, int port, File wwwroot) throws IOException {
        this(localAddr, port, wwwroot,null);
    }

    public WebServer(String localAddr, int port, File wwwroot, Handler handler) throws IOException {
        super(localAddr, port, wwwroot, true, "*");

        this.handler = handler;
        this.uploader = new NanoFileUpload(new DiskFileItemFactory());
        this.conditions = new HashMap<>();
        this.responses = new HashMap<>();
        this.lock = new ReentrantLock();

        mimeTypes().put("xhtml", "application/xhtml+xml");
        mimeTypes().put("opf", "application/oebps-package+xml");
        mimeTypes().put("ncx", "application/xml");
        mimeTypes().put("epub", "application/epub+zip");
        mimeTypes().put("otf", "application/x-font-otf");
        mimeTypes().put("ttf", "application/x-font-ttf");
        mimeTypes().put("js", "application/javascript");
        mimeTypes().put("json", "application/json");
        mimeTypes().put("svg", "image/svg+xml");
    }

    public void setResponse(String requestId, String response) {
        Condition condition = conditions.get(requestId);
        if (condition == null) return;

        lock.lock();
        responses.put(requestId, response);
        condition.signal();
        lock.unlock();
    }

    private String jsonMessage(String message) {
        return "{\"msg\":\"" + message + "\"}";
    }

    @Override
    public Response serve(IHTTPSession session) {
        // file & dynamic content
        List<FileItem> files = new ArrayList<>();
        if (this.uploader != null && NanoFileUpload.isMultipartContent(session)) {
            try {
                files = uploader.parseRequest(session);
            } catch (FileUploadException e) {
                e.printStackTrace();
            }
        }

        String uri = session.getUri();
        Response.IStatus status = Response.Status.OK;
        if (this.handler != null && (!files.isEmpty() || uri.startsWith("/rn/"))) {
            String requestId = "r" + System.currentTimeMillis() + new Random().nextInt();
            this.handler.handle(requestId, uri, session.getParameters(), files);

            String mime = mimeTypes().get("json");
            String message = jsonMessage("ok");

            // wait for response
            if (uri.startsWith("/rn/")) {
                lock.lock();
                Condition condition = lock.newCondition();
                conditions.put(requestId, condition);

                try {
                    boolean success = condition.await(handler.timeoutInMillis(), TimeUnit.MILLISECONDS);
                    if (!success) {
                        status = Response.Status.REQUEST_TIMEOUT;
                        message = jsonMessage("timeout");
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    status = Response.Status.INTERNAL_ERROR;
                    message = jsonMessage("interrupt");
                }

                conditions.remove(requestId);
                if (responses.containsKey(requestId)) {
                    message = responses.get(requestId);
                    responses.remove(requestId);
                }

                lock.unlock();
            }

            return newFixedLengthResponse(status, mime, message);
        }

        if (handler.tryAssets()) {
          // static serve
          uri = uri.trim().replace(File.separatorChar, '/');
          if (uri.indexOf('?') >= 0) {
              uri = uri.substring(0, uri.indexOf('?'));
          }
          // try assets
          if (this.handler != null) {
              InputStream asset = handler.assets(uri);
              if (asset != null) {
                  return newChunkedResponse(status, getMimeTypeForFile(uri), asset);
              }
          }
        }

        return super.serve(session);
    }

    @Override
    protected boolean useGzipWhenAccepted(Response r) {
        return super.useGzipWhenAccepted(r) && r.getStatus() != Response.Status.NOT_MODIFIED;
    }
}
