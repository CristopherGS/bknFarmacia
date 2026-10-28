package com.sglabs.farmacia;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/public/leads")
public class LeadController {
  private static final Logger LOG = LoggerFactory.getLogger(LeadController.class);

  @Value("${LEADS_WEBHOOK_URL:}") String webhook;
  private final ObjectMapper mapper;
  LeadController(ObjectMapper mapper) { this.mapper = mapper; }
  record Lead(@NotBlank @Size(max=100) String name, @NotBlank @Email @Size(max=200) String email,
              @NotBlank @Size(max=100) String project, @NotBlank @Size(max=2000) String message,
              @Size(max=100) String estimate, @Pattern(regexp="es|en") String language) {}
  @PostMapping
  ResponseEntity<?> submit(@Valid @RequestBody Lead lead) {
    if (webhook.isBlank()) {
      LOG.error("Lead webhook no configurado; no se puede enviar la consulta");
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Contact service is not configured");
    }
    long started = System.nanoTime();
    URI destination;
    try {
      destination = URI.create(webhook);
      byte[] body = mapper.writeValueAsBytes(lead);
      LOG.info(
          "HTTP saliente iniciado: método=POST, destino={}, timeoutMs=8000, connectTimeoutMs=3000, bytesEnviados={}",
          HttpTrace.target(destination),
          body.length);
      var request = HttpRequest.newBuilder(destination).timeout(Duration.ofSeconds(8))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
      var response =
          HttpClient.newBuilder()
              .version(HttpClient.Version.HTTP_1_1)
              .connectTimeout(Duration.ofMillis(3000))
              .build()
              .send(request, HttpResponse.BodyHandlers.ofString());
      long elapsedMs = (System.nanoTime() - started) / 1_000_000;
      LOG.info(
          "HTTP saliente completado: método=POST, destino={}, status={}, duraciónMs={}, bytesEnviados={}, bytesRecibidos={}, respuesta={}",
          HttpTrace.target(destination),
          response.statusCode(),
          elapsedMs,
          body.length,
          response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
          preview(response.body()));
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "HTTP " + response.statusCode() + " respuesta=" + preview(response.body()));
      }
      return responseBody(response);
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      LOG.error(
          "HTTP saliente fallido: método=POST, destino={}, duraciónMs={}, excepción={}",
          safeTarget(webhook),
          (System.nanoTime() - started) / 1_000_000,
          HttpTrace.exception(e),
          e);
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "Contact service unavailable", e);
    }
  }

  private static String safeTarget(String value) {
    try {
      return HttpTrace.target(URI.create(value));
    } catch (RuntimeException e) {
      return "<URL inválida>";
    }
  }

  private ResponseEntity<?> responseBody(HttpResponse<String> response) {
    String contentType = response.headers().firstValue("Content-Type").orElse("");
    String body = response.body() == null ? "" : response.body().trim();
    if (body.isEmpty()) return ResponseEntity.ok(Map.of("ok", true));
    try {
      var jsonBody = mapper.readTree(body);
      if (jsonBody != null && !jsonBody.isTextual()) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(jsonBody);
      }
    } catch (Exception e) {
      if (contentType.toLowerCase().contains(MediaType.APPLICATION_JSON_VALUE)) {
        LOG.warn("n8n respondió Content-Type JSON inválido: {}", e.getMessage());
      }
    }
    return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(body);
  }

  private static String preview(String body) {
    if (body == null || body.isBlank()) return "<vacía>";
    String compact = body.replaceAll("\\s+", " ").trim();
    return compact.length() > 1000 ? compact.substring(0, 1000) + "…" : compact;
  }
}
