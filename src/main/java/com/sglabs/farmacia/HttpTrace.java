package com.sglabs.farmacia;

import java.net.URI;

/** Formats HTTP destinations without logging query strings or credentials. */
public final class HttpTrace {
  private HttpTrace() {}

  public static String target(URI uri) {
    String host = uri.getHost();
    if (host == null || host.isBlank()) host = uri.getAuthority();
    String path = uri.getRawPath();
    if (path == null || path.isBlank()) path = "/";
    return uri.getScheme() + "://" + host + path;
  }
}
