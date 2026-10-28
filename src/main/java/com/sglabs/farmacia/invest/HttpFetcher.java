package com.sglabs.farmacia.invest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Acceso HTTP mínimo. Es una interfaz para poder probar las fuentes sin red. */
interface HttpFetcher {
  String get(String url, Map<String, String> headers) throws IOException;

  String post(String url, String body, Map<String, String> headers) throws IOException;

  /** Implementación real, con tiempos límite para que una fuente lenta no bloquee el trabajo diario. */
  final class Jdk implements HttpFetcher {
    private static final Logger LOG = LoggerFactory.getLogger(Jdk.class);
    private final HttpClient client =
        HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();

    private String send(HttpRequest.Builder b, Map<String, String> headers) throws IOException {
      b.timeout(Duration.ofSeconds(20)).header("User-Agent", "Mozilla/5.0 (compatible; portafolio-personal/1.0)");
      headers.forEach(b::header);
      HttpRequest request = b.build();
      long started = System.nanoTime();
      try {
        var r = client.send(request, HttpResponse.BodyHandlers.ofString());
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        LOG.info(
            "HTTP saliente: método={}, host={}, ruta={}, status={}, duraciónMs={}, bytesEnviados={}",
            request.method(),
            com.sglabs.farmacia.HttpTrace.target(request.uri()),
            request.uri().getRawPath(),
            r.statusCode(),
            elapsedMs,
            request.bodyPublisher().map(p -> p.contentLength()).orElse(0L));
        if (r.statusCode() / 100 != 2) throw new IOException("HTTP " + r.statusCode());
        return r.body();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        LOG.error(
            "HTTP saliente interrumpido: método={}, host={}, ruta={}",
            request.method(),
            com.sglabs.farmacia.HttpTrace.target(request.uri()),
            request.uri().getRawPath());
        throw new IOException("Interrumpido", e);
      } catch (IOException e) {
        LOG.error(
            "HTTP saliente fallido: método={}, host={}, ruta={}, tipo={}, mensaje={}",
            request.method(),
            com.sglabs.farmacia.HttpTrace.target(request.uri()),
            request.uri().getRawPath(),
            e.getClass().getSimpleName(),
            e.getMessage());
        throw e;
      }
    }

    @Override
    public String get(String url, Map<String, String> headers) throws IOException {
      return send(HttpRequest.newBuilder(URI.create(url)).GET(), headers);
    }

    @Override
    public String post(String url, String body, Map<String, String> headers) throws IOException {
      return send(HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString(body)), headers);
    }
  }
}
