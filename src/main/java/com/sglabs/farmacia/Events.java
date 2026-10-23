package com.sglabs.farmacia;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Registra eventos de negocio en el outbox dentro de la misma transacción que el cambio que los
 * origina; {@link OutboxPublisher} los entrega después a n8n. Sin webhook configurado no se guarda
 * nada, para no acumular una cola que nadie va a leer.
 */
@Component
public class Events {
  private final JdbcTemplate db;
  private final Settings settings;
  private final ObjectMapper json;

  public Events(JdbcTemplate db, Settings settings, ObjectMapper json) {
    this.db = db;
    this.settings = settings;
    this.json = json;
  }

  public boolean enabled() {
    return settings.get().webhookConfigured();
  }

  public void emit(String type, long aggregateId, Map<String, ?> data) {
    if (!enabled()) return;
    try {
      db.update(
          "INSERT INTO outbox_events(event_type,aggregate_id,payload) VALUES (?,?,?::jsonb)",
          type,
          aggregateId,
          json.writeValueAsString(data));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("No se pudo registrar el evento " + type, e);
    }
  }
}
