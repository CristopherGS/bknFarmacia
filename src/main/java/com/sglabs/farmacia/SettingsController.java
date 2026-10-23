package com.sglabs.farmacia;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.security.Principal;
import java.security.SecureRandom;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Marca, módulos e integración con n8n del negocio. La configuración es solo del ADMIN. */
@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {
  private static final SecureRandom RANDOM = new SecureRandom();

  private final JdbcTemplate db;
  private final Settings settings;
  private final Operations op;
  private final OutboxPublisher publisher;

  SettingsController(JdbcTemplate db, Settings settings, Operations op, OutboxPublisher publisher) {
    this.db = db;
    this.settings = settings;
    this.op = op;
    this.publisher = publisher;
  }

  record SettingsUpdate(
      @NotNull @Size(min = 2, max = 120) String name,
      @NotNull @Pattern(regexp = "FARMACIA|TIENDA|OTRO") String type,
      @NotNull @Pattern(regexp = "#[0-9a-fA-F]{6}") String color,
      @NotNull List<String> modules,
      @NotNull @Size(max = 500) String webhookUrl) {}

  /** Lo único que ve quien todavía no ha iniciado sesión: el nombre y el color de la marca. */
  @GetMapping("/public")
  Object publicBrand() {
    var s = settings.get();
    var type = Modules.type(s.type());
    return Map.of("name", s.name(), "type", s.type(), "noun", type.noun(), "color", s.color());
  }

  @GetMapping
  @PreAuthorize("@perm.admin()")
  Object get() {
    var s = settings.get();
    var out = new LinkedHashMap<String, Object>();
    out.put("name", s.name());
    out.put("type", s.type());
    out.put("color", s.color());
    out.put("modules", Modules.normalize(s.modules()));
    out.put("catalog", Modules.ALL);
    out.put("roadmap", Modules.ROADMAP);
    out.put("types", Modules.TYPES);
    out.put("webhookUrl", s.webhookUrl());
    // El secreto no se devuelve: solo se muestra una vez al generarlo.
    out.put("webhookSecretSet", !s.webhookSecret().isBlank());
    out.put("queue", publisher.queueStatus());
    return out;
  }

  @PutMapping
  @PreAuthorize("@perm.admin()")
  @Transactional
  Object update(@Valid @RequestBody SettingsUpdate u, Principal p) {
    var modules = Modules.normalize(u.modules());
    Modules.type(u.type());
    String url = u.webhookUrl().trim();
    if (!url.isEmpty()) {
      URI uri;
      try {
        uri = URI.create(url);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("La URL del webhook no es válida.");
      }
      if (uri.getHost() == null || !List.of("http", "https").contains(String.valueOf(uri.getScheme())))
        throw new IllegalArgumentException("La URL del webhook debe empezar con http:// o https://");
    }
    db.update(
        "UPDATE settings SET business_name=?,business_type=?,primary_color=?,webhook_url=?,"
            + "updated_at=CURRENT_TIMESTAMP WHERE id=1",
        u.name().trim(),
        u.type(),
        u.color().toLowerCase(),
        url);
    settings.replaceModules(modules);
    op.audit(op.branch(p.getName()), p.getName(), "SETTINGS_CHANGED", "negocio");
    return Map.of("ok", true);
  }

  /** Genera un secreto nuevo para firmar los eventos. Se devuelve una sola vez. */
  @PostMapping("/webhook/secret")
  @PreAuthorize("@perm.admin()")
  @Transactional
  Object newSecret(Principal p) {
    var bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    String secret = "whsec_" + HexFormat.of().formatHex(bytes);
    db.update("UPDATE settings SET webhook_secret=?,updated_at=CURRENT_TIMESTAMP WHERE id=1", secret);
    op.audit(op.branch(p.getName()), p.getName(), "WEBHOOK_SECRET_ROTATED", "n8n");
    return Map.of("secret", secret);
  }

  @PostMapping("/webhook/test")
  @PreAuthorize("@perm.admin()")
  Object test() {
    return publisher.sendTest();
  }
}
