
package com.futurepress.staticserver;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;

import android.util.Log;


import org.apache.commons.fileupload.FileItem;

import fi.iki.elonen.SimpleWebServer;

public class FPStaticServerModule extends ReactContextBaseJavaModule implements LifecycleEventListener, WebServer.Handler {

  private final ReactApplicationContext reactContext;

  private static final String LOGTAG = "FPStaticServerModule";

  private File www_root = null;
  private int port = 9999;
  private int timeoutInMillis = 3000;
  private File upload_dir = null;
  private boolean localhost_only = false;
  private boolean keep_alive = false;

  private String localPath = "";
  private WebServer server = null;
  private String	url = "";

  public FPStaticServerModule(ReactApplicationContext reactContext) {
    super(reactContext);
    this.reactContext = reactContext;
  }

  private String __getLocalIpAddress() {
    try {
      for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
        NetworkInterface intf = en.nextElement();
        for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
          InetAddress inetAddress = enumIpAddr.nextElement();
          if (! inetAddress.isLoopbackAddress()) {
            String ip = inetAddress.getHostAddress();
            if(InetAddressUtils.isIPv4Address(ip)) {
              Log.w(LOGTAG, "local IP: "+ ip);
              return ip;
            }
          }
        }
      }
    } catch (SocketException ex) {
      Log.e(LOGTAG, ex.toString());
    }

    return "127.0.0.1";
  }

  @Override
  public String getName() {
    return "FPStaticServer";
  }

  @ReactMethod
  public void start(String _port, String root, Boolean localhost, Boolean keepAlive, String uploadDir, int timeoutInMillis, Promise promise) {

    this.timeoutInMillis = timeoutInMillis;

    if (server != null){
      promise.resolve(url);
      return;
    }

    if (_port != null) {
      try {
        port = Integer.parseInt(_port);

        if (port == 0) {
          try {
            port = this.findRandomOpenPort();
          } catch (IOException e) {
            port = 9999;
          }
        }
      } catch(NumberFormatException nfe) {
        try {
          port = this.findRandomOpenPort();
        } catch (IOException e) {
          port = 9999;
        }
      }
    }

    if(uploadDir != null && (uploadDir.startsWith("/") || uploadDir.startsWith("file:///"))) {
      upload_dir = new File(uploadDir);
    }

    if(root != null && (root.startsWith("/") || root.startsWith("file:///"))) {
      www_root = new File(root);
      localPath = www_root.getAbsolutePath();
    } else {
      www_root = new File(this.reactContext.getFilesDir(), root);
      localPath = www_root.getAbsolutePath();
    }

    if (localhost != null) {
      localhost_only = localhost;
    }

    if (keepAlive != null) {
      keep_alive = keepAlive;
    }

    try {

      if(localhost_only) {
        server = new WebServer("localhost", port, www_root, this);
      } else {
        server = new WebServer(__getLocalIpAddress(), port, www_root, this);
      }


      if (localhost_only) {
        url = "http://localhost:" + port;
      } else {
        url = "http://" + __getLocalIpAddress() + ":" + port;
      }

      server.start();

      promise.resolve(url);

    } catch (IOException e) {
      String msg = e.getMessage();



      // Server doesn't stop on refresh
      if (server != null && msg.equals("bind failed: EADDRINUSE (Address already in use)")){
        promise.resolve(url);
      } else {
        promise.reject(null, msg);
      }

    }


  }

  private Integer findRandomOpenPort() throws IOException {
    try {
      ServerSocket socket = new ServerSocket(0);
      int port = socket.getLocalPort();
      Log.w(LOGTAG, "port:" + port);
      socket.close();
      return port;
    } catch (IOException e) {
      return 0;
    }
  }

  @ReactMethod
  public void stop() {
    if (server != null) {
      Log.w(LOGTAG, "Stopped Server");
      server.stop();
      server = null;
    }
  }

  @ReactMethod
  public void response(String requestId, String response) {
    if (server != null) {
      server.setResponse(requestId, response);
    }
  }

  @ReactMethod
  public void origin(Promise promise) {
    if (server != null) {
      promise.resolve(url);
    } else {
      promise.resolve("");
    }
  }

  /* Shut down the server if app is destroyed or paused */
  @Override
  public void onHostResume() {
    //start(null, null, null, null);
  }

  @Override
  public void onHostPause() {
    //stop();
  }

  @Override
  public void onHostDestroy() {
    stop();
  }

  @Override
  public void handle(String id, String uri, Map<String, List<String>> params, List<FileItem> files) {
    List<String> filePaths = new ArrayList<>();
    if (upload_dir != null) {
      for (FileItem item : files) {
        File saveFile = new File(upload_dir, item.getName());
        try {
          item.write(saveFile);
          filePaths.add(saveFile.getAbsolutePath());
        } catch (Exception e) {
          Log.e(LOGTAG, e.toString());
        }
      }
    }

    WritableMap jsParams = Arguments.createMap();
    jsParams.putString("__id", id);
    jsParams.putString("__uri", uri);
    for (Map.Entry<String, List<String>> entry : params.entrySet()) {
      if (entry.getValue().size() == 1) {
        jsParams.putString(entry.getKey(), entry.getValue().get(0));
      } else {
        jsParams.putArray(entry.getKey(), Arguments.makeNativeArray(entry.getValue()));
      }
    }
    jsParams.putArray("__files", Arguments.makeNativeArray(filePaths));

    reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit("webServerRNRequest", jsParams);
  }

  @Override
  public int timeoutInMillis() {
    return timeoutInMillis;
  }

  @Override
  public InputStream assets(String uri) {
    try {
      return reactContext.getAssets().open("/www" + uri);
    } catch (IOException e) {
      return null;
    }
  }
}
