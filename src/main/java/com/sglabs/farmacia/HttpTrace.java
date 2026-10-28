package com.sglabs.farmacia;

import java.net.URI;

/** Formats HTTP destinations without logging query strings or credentials. */
public final class HttpTrace {
  private HttpTrace() {}

  public static String target(URI uri) {
    String host = uri.getHost();
    if (host == null || host.isBlank()) host = uri.getAuthority();
    int port = uri.getPort();
    String portText = port > 0 ? ":" + port : "";
    String path = uri.getRawPath();
    if (path == null || path.isBlank()) path = "/";
    return uri.getScheme() + "://" + host + portText + path;
  }

  public static String exception(Throwable error) {
    StringBuilder out = new StringBuilder();
    Throwable current = error;
    int depth = 0;
    while (current != null && depth++ < 8) {
      if (out.length() > 0) out.append(" -> ");
      out.append(current.getClass().getSimpleName());
      if (current.getMessage() != null && !current.getMessage().isBlank()) {
        out.append(": ").append(current.getMessage());
      }
      current = current.getCause();
    }
    return out.toString();
  }
}
