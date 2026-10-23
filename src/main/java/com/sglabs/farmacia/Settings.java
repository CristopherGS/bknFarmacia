package com.sglabs.farmacia;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Ajustes del negocio de este despliegue: marca, módulos activos e integración con n8n. */
@Component
public class Settings {
  public record Snapshot(
      String name,
      String type,
      String color,
      Set<String> modules,
      String webhookUrl,
      String webhookSecret) {
    public boolean module(String key) {
      return modules.contains(key);
    }

    public boolean webhookConfigured() {
      return !webhookUrl.isBlank() && !webhookSecret.isBlank();
    }
  }

  private final JdbcTemplate db;

  public Settings(JdbcTemplate db) {
    this.db = db;
  }

  /** Se lee de la base en cada uso: un cambio del administrador aplica al instante. */
  public Snapshot get() {
    return db.queryForObject(
        "SELECT business_name,business_type,primary_color,webhook_url,webhook_secret,(SELECT"
            + " COALESCE(string_agg(module,','),'') FROM enabled_modules) AS modules FROM settings"
            + " WHERE id=1",
        (r, n) -> {
          String modules = r.getString("modules");
          return new Snapshot(
              r.getString("business_name"),
              r.getString("business_type"),
              r.getString("primary_color"),
              modules.isEmpty()
                  ? Set.of()
                  : Collections.unmodifiableSet(new LinkedHashSet<>(List.of(modules.split(",")))),
              r.getString("webhook_url"),
              r.getString("webhook_secret"));
        });
  }

  public boolean module(String key) {
    return get().module(key);
  }

  void replaceModules(Collection<String> modules) {
    db.update("DELETE FROM enabled_modules");
    for (String m : modules) db.update("INSERT INTO enabled_modules(module) VALUES (?)", m);
  }
}
