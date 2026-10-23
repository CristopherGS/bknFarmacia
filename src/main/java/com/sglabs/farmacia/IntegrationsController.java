package com.sglabs.farmacia;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.SecureRandom;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Llaves para que n8n (u otra herramienta) consulte el sistema. Cada llave es una cuenta de servicio
 * con solo los permisos que el administrador le dio; se envía como {@code Authorization: Bearer
 * sgk_...} y no usa sesión ni CSRF.
 */
@RestController
@RequestMapping("/api/v1/integrations/keys")
@PreAuthorize("@perm.admin()")
public class IntegrationsController {
  static final String PREFIX = "sgk_";
  private static final SecureRandom RANDOM = new SecureRandom();

  private final JdbcTemplate db;
  private final Operations op;
  private final PasswordEncoder encoder;

  IntegrationsController(JdbcTemplate db, Operations op, PasswordEncoder encoder) {
    this.db = db;
    this.op = op;
    this.encoder = encoder;
  }

  record NewKey(@NotNull @Size(min = 2, max = 60) String label, @NotNull List<String> permissions) {}

  static String hash(String key) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @GetMapping
  Object list(Principal p) {
    var keys =
        db.queryForList(
            "SELECT k.id,k.label,k.prefix,k.created_at,k.last_used_at,k.revoked_at,u.id AS user_id"
                + " FROM api_keys k JOIN users u ON u.id=k.user_id WHERE u.branch_id=? ORDER BY"
                + " k.id DESC",
            op.branch(p.getName()));
    for (var k : keys) {
      long userId = ((Number) k.remove("user_id")).longValue();
      k.put(
          "permissions",
          Permissions.normalize(
              new HashSet<>(
                  db.queryForList(
                      "SELECT permission FROM user_permissions WHERE user_id=?",
                      String.class,
                      userId))));
    }
    return keys;
  }

  /** Crea la cuenta de servicio y su llave. La llave completa se devuelve una sola vez. */
  @PostMapping
  @Transactional
  Object create(@Valid @RequestBody NewKey k, Principal p) {
    long branch = op.branch(p.getName());
    var permissions = Permissions.normalize(new HashSet<>(k.permissions()));
    if (permissions.isEmpty())
      throw new IllegalArgumentException("Elige al menos un permiso para la llave.");
    var raw = new byte[32];
    RANDOM.nextBytes(raw);
    String key = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    String slug = k.label().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    String username =
        "svc-" + (slug.isEmpty() ? "llave" : slug) + "-" + Integer.toHexString(RANDOM.nextInt(0xffff));
    long userId =
        db.queryForObject(
            "INSERT INTO users(username,password_hash,role,branch_id) VALUES (?,?,'SERVICE',?)"
                + " RETURNING id",
            Long.class,
            username,
            // Contraseña aleatoria que nadie conoce: la cuenta solo entra con su llave.
            encoder.encode(Base64.getEncoder().encodeToString(raw)),
            branch);
    for (String perm : permissions)
      db.update("INSERT INTO user_permissions(user_id,permission) VALUES (?,?)", userId, perm);
    long id =
        db.queryForObject(
            "INSERT INTO api_keys(user_id,label,prefix,key_hash,created_by) VALUES (?,?,?,?,?)"
                + " RETURNING id",
            Long.class,
            userId,
            k.label().trim(),
            key.substring(0, 12),
            hash(key),
            p.getName());
    op.audit(branch, p.getName(), "INTEGRATION_KEY_CREATED", k.label().trim());
    return Map.of("id", id, "key", key, "username", username, "permissions", permissions);
  }

  @DeleteMapping("/{id}")
  @Transactional
  Object revoke(@PathVariable long id, Principal p) {
    long branch = op.branch(p.getName());
    var key =
        db.queryForMap(
            "SELECT k.label,k.user_id FROM api_keys k JOIN users u ON u.id=k.user_id WHERE k.id=?"
                + " AND u.branch_id=?",
            id,
            branch);
    db.update("UPDATE api_keys SET revoked_at=CURRENT_TIMESTAMP WHERE id=? AND revoked_at IS NULL", id);
    db.update("UPDATE users SET active=FALSE WHERE id=?", key.get("user_id"));
    op.audit(branch, p.getName(), "INTEGRATION_KEY_REVOKED", (String) key.get("label"));
    return Map.of("ok", true);
  }
}
