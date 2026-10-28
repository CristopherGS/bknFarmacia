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
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/public/leads")
public class LeadController {
  @Value("${LEADS_WEBHOOK_URL:}") String webhook;
  private final ObjectMapper mapper;
  LeadController(ObjectMapper mapper) { this.mapper = mapper; }
  record Lead(@NotBlank @Size(max=100) String name, @NotBlank @Email @Size(max=200) String email,
              @NotBlank @Size(max=100) String project, @NotBlank @Size(max=2000) String message,
              @Size(max=100) String estimate, @Pattern(regexp="es|en") String language) {}
  @PostMapping
  Map<String, Boolean> submit(@Valid @RequestBody Lead lead) {
    if (webhook.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Contact service is not configured");
    try {
      var request = HttpRequest.newBuilder(URI.create(webhook)).timeout(Duration.ofSeconds(8))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(lead))).build();
      var response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build().send(request, HttpResponse.BodyHandlers.discarding());
      if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException();
      return Map.of("ok", true);
    } catch (Exception e) { if (e instanceof InterruptedException) Thread.currentThread().interrupt(); throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Contact service unavailable"); }
  }
}
