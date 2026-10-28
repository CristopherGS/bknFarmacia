package com.sglabs.farmacia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Entrega los eventos del outbox al webhook de n8n. Cada envío va firmado: el encabezado
 * {@code X-SG-Signature: t=<epoch>,v1=<hmac-sha256 hex>} firma {@code "<epoch>.<cuerpo>"} con el
 * secreto del webhook. Si el destino falla, reintenta con espera creciente; varias réplicas pueden
 * correr a la vez porque cada una reclama sus filas con SKIP LOCKED.
 */
@Component
public class OutboxPublisher {
  private static final Logger LOG = LoggerFactory.getLogger(OutboxPublisher.class);
  static final int MAX_ATTEMPTS = 8;

  private final JdbcTemplate db;
  private final Settings settings;
  private final ObjectMapper json;
  private final long backoffSeconds;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  public OutboxPublisher(
      JdbcTemplate db,
      Settings settings,
      ObjectMapper json,
      @Value("${app.outbox.backoff-seconds:10}") long backoffSeconds) {
    this.db = db;
    this.settings = settings;
    this.json = json;
    this.backoffSeconds = backoffSeconds;
  }

  public record Result(boolean ok, int status, String error) {}

  /** Firma de entrega; n8n la verifica con el mismo secreto (ver INTEGRACION_N8N.md). */
  public static String sign(String secret, long timestamp, String body) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of()
          .formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("No se pudo firmar el evento", e);
    }
  }

  @Scheduled(fixedDelayString = "${app.outbox.interval-ms:5000}", initialDelay = 5000)
  public void run() {
    try {
      publishPending();
    } catch (RuntimeException e) {
      LOG.error("No se pudo procesar la cola de eventos", e);
    }
  }

  /** Envía los eventos pendientes que ya toca intentar; devuelve cuántos se entregaron. */
  public int publishPending() {
    var s = settings.get();
    if (!s.webhookConfigured()) return 0;
    // Reclama las filas y las posterga 2 minutos: si esta réplica se cae a medio envío, otra las
    // retoma sin que dos las envíen a la vez.
    var rows =
        db.queryForList(
            "UPDATE outbox_events SET attempts=attempts+1, next_attempt_at=CURRENT_TIMESTAMP +"
                + " interval '2 minutes' WHERE id IN (SELECT id FROM outbox_events WHERE"
                + " status='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP ORDER BY id LIMIT 20 FOR"
                + " UPDATE SKIP LOCKED) RETURNING id,event_type,payload::text AS payload,attempts,"
                + "created_at");
    if (!rows.isEmpty()) {
      LOG.info("Procesando {} evento(s) pendientes para n8n", rows.size());
    }
    int delivered = 0;
    for (var row : rows) {
      long id = ((Number) row.get("id")).longValue();
      int attempts = ((Number) row.get("attempts")).intValue();
      try {
        var data = json.readTree((String) row.get("payload"));
        var result =
            post(
                s,
                id,
                (String) row.get("event_type"),
                ((java.sql.Timestamp) row.get("created_at")).toInstant(),
                data);
        if (result.ok()) {
          db.update(
              "UPDATE outbox_events SET status='SENT',delivered_at=CURRENT_TIMESTAMP,last_error=''"
                  + " WHERE id=?",
              id);
          delivered++;
          LOG.info(
              "Evento n8n entregado: id={}, tipo={}, intento={}, status={}",
              id,
              row.get("event_type"),
              attempts,
              result.status());
        } else {
          fail(id, attempts, result.error());
        }
      } catch (Exception e) {
        LOG.error(
            "Error procesando evento n8n: id={}, tipo={}, intento={}, excepción={}",
            id,
            row.get("event_type"),
            attempts,
            HttpTrace.exception(e),
            e);
        fail(id, attempts, e.getClass().getSimpleName() + ": " + e.getMessage());
      }
    }
    return delivered;
  }

  private void fail(long id, int attempts, String error) {
    String shortError = error == null ? "" : error.substring(0, Math.min(error.length(), 290));
    if (attempts >= MAX_ATTEMPTS) {
      LOG.warn("Evento {} descartado tras {} intentos: {}", id, attempts, shortError);
      db.update("UPDATE outbox_events SET status='FAILED',last_error=? WHERE id=?", shortError, id);
    } else {
      long wait = Math.min(3600, backoffSeconds << (attempts - 1));
      LOG.warn(
          "Evento n8n falló: id={}, intento={}, próximoIntentoEn={}s, causa={}",
          id,
          attempts,
          wait,
          shortError);
      db.update(
          "UPDATE outbox_events SET last_error=?,next_attempt_at=CURRENT_TIMESTAMP + (? * interval"
              + " '1 second') WHERE id=?",
          shortError,
          wait,
          id);
    }
  }

  /** Envío directo de prueba desde la pantalla de configuración. */
  public Result sendTest() {
    var s = settings.get();
    if (!s.webhookConfigured()) return new Result(false, 0, "Falta la URL o el secreto del webhook.");
    var data = json.createObjectNode().put("message", "Prueba de conexión desde " + s.name());
    try {
      var result = post(s, 0, "test.ping", Instant.now(), data);
      LOG.info("Prueba de webhook n8n: status={}, ok={}", result.status(), result.ok());
      return result;
    } catch (Exception e) {
      LOG.error(
          "Error en prueba de webhook n8n: tipo={}, mensaje={}",
          e.getClass().getSimpleName(),
          e.getMessage());
      return new Result(false, 0, e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private Result post(Settings.Snapshot s, long id, String type, Instant at, JsonNode data)
      throws Exception {
    ObjectNode envelope = json.createObjectNode();
    envelope.put("id", id);
    envelope.put("type", type);
    envelope.put("occurredAt", at.toString());
    envelope.put("business", s.name());
    envelope.set("data", data);
    String body = json.writeValueAsString(envelope);
    long ts = Instant.now().getEpochSecond();
    URI destination = URI.create(s.webhookUrl());
    LOG.info(
        "HTTP saliente n8n iniciado: método=POST, destino={}, eventoId={}, tipo={}, timeoutMs=8000, bytesEnviados={}",
        HttpTrace.target(destination),
        id,
        type,
        body.getBytes(StandardCharsets.UTF_8).length);
    long started = System.nanoTime();
    var request =
        HttpRequest.newBuilder(destination)
            .timeout(Duration.ofSeconds(8))
            .header("Content-Type", "application/json")
            .header("User-Agent", "SG-Suite-Webhook/1")
            .header("X-SG-Event", type)
            .header("X-SG-Delivery", Long.toString(id))
            .header("X-SG-Signature", "t=" + ts + ",v1=" + sign(s.webhookSecret(), ts, body))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    var response = http.send(request, HttpResponse.BodyHandlers.discarding());
    int code = response.statusCode();
    LOG.info(
        "HTTP saliente n8n completado: método=POST, destino={}, eventoId={}, tipo={}, status={}, duraciónMs={}, bytesEnviados={}",
        HttpTrace.target(destination),
        id,
        type,
        code,
        (System.nanoTime() - started) / 1_000_000,
        body.getBytes(StandardCharsets.UTF_8).length);
    return code / 100 == 2 ? new Result(true, code, "") : new Result(false, code, "HTTP " + code);
  }

  /** Cuántos eventos hay por estado (PENDING, SENT, FAILED) para mostrarlo en la configuración. */
  public List<Map<String, Object>> queueStatus() {
    return db.queryForList(
        "SELECT status, COUNT(*) AS total FROM outbox_events WHERE status<>'SKIPPED' GROUP BY"
            + " status ORDER BY status");
  }

  List<String> pendingTypes() {
    return db.queryForList(
        "SELECT event_type FROM outbox_events WHERE status='PENDING' ORDER BY id", String.class);
  }
}
