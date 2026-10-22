package com.sglabs.farmacia;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Administración de usuarios y de lo que cada uno puede ver y hacer. Solo para ADMIN. */
@RestController
@RequestMapping("/api/v1/users")
public class UsersController {
  final Operations op;
  final org.springframework.jdbc.core.JdbcTemplate db;
  final PasswordEncoder encoder;

  UsersController(
      Operations op, PasswordEncoder encoder, org.springframework.jdbc.core.JdbcTemplate db) {
    this.db = db;
    this.op = op;
    this.encoder = encoder;
  }

  record NewUser(
      @Pattern(regexp = "[a-zA-Z0-9._-]{3,80}") @NotNull String username,
      @Size(min = 14, max = 72) @NotNull String password,
      @Pattern(regexp = "ADMIN|SUPERVISOR|CASHIER|INVENTORY|ACCOUNTANT") @NotNull String role,
      List<String> permissions) {}

  record UserUpdate(
      @Pattern(regexp = "ADMIN|SUPERVISOR|CASHIER|INVENTORY|ACCOUNTANT") String role,
      List<String> permissions,
      Boolean active) {}

  record PasswordChange(@Size(min = 14, max = 72) @NotNull String password) {}

  /** Catálogo de permisos y plantillas por rol, para armar la pantalla de administración. */
  @GetMapping("/permissions")
  @PreAuthorize("@perm.admin()")
  Object catalog() {
    return Map.of(
        "permissions", Permissions.ALL, "roles", Permissions.presets(), "implies", Permissions.IMPLIES);
  }

  @GetMapping
  @PreAuthorize("@perm.admin()")
  Object list(Principal p) {
    var granted = new HashMap<Long, List<String>>();
    db.query(
        "SELECT p.user_id,p.permission FROM user_permissions p JOIN users u ON u.id=p.user_id"
            + " WHERE u.branch_id=? ORDER BY p.permission",
        rs -> {
          granted
              .computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
              .add(rs.getString(2));
        },
        op.branch(p.getName()));
    var users =
        db.queryForList(
            "SELECT id,username,role,active FROM users WHERE branch_id=? ORDER BY username",
            op.branch(p.getName()));
    for (var u : users) {
      long id = ((Number) u.get("id")).longValue();
      u.put(
          "permissions",
          "ADMIN".equals(u.get("role"))
              ? List.copyOf(Permissions.KEYS)
              : Permissions.normalize(granted.getOrDefault(id, List.of())));
    }
    return users;
  }

  @PostMapping
  @PreAuthorize("@perm.admin()")
  @Transactional
  Object create(@Valid @RequestBody NewUser u, Principal p) {
    long branch = op.branch(p.getName());
    var permissions =
        u.permissions() == null
            ? Permissions.preset(u.role())
            : Permissions.normalize(new HashSet<>(u.permissions()));
    long id =
        db.queryForObject(
            "INSERT INTO users(username,password_hash,role,branch_id) VALUES (?,?,?,?) RETURNING"
                + " id",
            Long.class,
            u.username(),
            encoder.encode(u.password()),
            u.role(),
            branch);
    if (!u.role().equals("ADMIN")) grant(id, permissions);
    op.audit(branch, p.getName(), "USER_CREATED", u.username());
    for (String k : permissions)
      if (!u.role().equals("ADMIN"))
        op.audit(branch, p.getName(), "PERMISSION_GRANTED", u.username() + ":" + k);
    return Map.of("ok", true, "id", id);
  }

  @PutMapping("/{id}")
  @PreAuthorize("@perm.admin()")
  @Transactional
  Object update(@PathVariable long id, @Valid @RequestBody UserUpdate change, Principal p) {
    long branch = op.branch(p.getName());
    // Serializa los cambios de administradores para que dos peticiones no dejen la sucursal sin
    // ninguno.
    db.queryForMap("SELECT id FROM branches WHERE id=? FOR UPDATE", branch);
    var target =
        db.queryForMap(
            "SELECT id,username,role,active FROM users WHERE id=? AND branch_id=?", id, branch);
    String name = (String) target.get("username");
    String oldRole = (String) target.get("role");
    boolean wasActive = (Boolean) target.get("active");
    String role = change.role() == null ? oldRole : change.role();
    boolean active = change.active() == null ? wasActive : change.active();
    boolean self = name.equals(p.getName());
    if (self && (!active || !role.equals("ADMIN")))
      throw new IllegalStateException(
          "No puede desactivarse ni quitarse el rol de administrador a sí mismo.");
    var current = currentPermissions(id);
    var wanted =
        change.permissions() == null
            ? (role.equals(oldRole) ? current : Permissions.preset(role))
            : Permissions.normalize(new HashSet<>(change.permissions()));

    db.update("UPDATE users SET role=?,active=? WHERE id=?", role, active, id);
    if (db.queryForObject(
            "SELECT COUNT(*) FROM users WHERE branch_id=? AND role='ADMIN' AND active",
            Integer.class,
            branch)
        < 1)
      throw new IllegalStateException("Debe quedar al menos un administrador activo.");

    if (!role.equals(oldRole))
      op.audit(branch, p.getName(), "USER_ROLE_CHANGED", name + ":" + role);
    if (active != wasActive)
      op.audit(branch, p.getName(), active ? "USER_ACTIVATED" : "USER_DEACTIVATED", name);
    if (role.equals("ADMIN")) {
      db.update("DELETE FROM user_permissions WHERE user_id=?", id);
    } else {
      var added = wanted.stream().filter(k -> !current.contains(k)).toList();
      var removed = current.stream().filter(k -> !wanted.contains(k)).toList();
      db.update("DELETE FROM user_permissions WHERE user_id=?", id);
      grant(id, wanted);
      for (String k : added) op.audit(branch, p.getName(), "PERMISSION_GRANTED", name + ":" + k);
      for (String k : removed) op.audit(branch, p.getName(), "PERMISSION_REVOKED", name + ":" + k);
    }
    return Map.of("ok", true);
  }

  /** El administrador restablece la contraseña de otra persona de su sucursal. */
  @PostMapping("/{id}/password")
  @PreAuthorize("@perm.admin()")
  @Transactional
  Object reset(@PathVariable long id, @Valid @RequestBody PasswordChange change, Principal p) {
    long branch = op.branch(p.getName());
    var name =
        db.queryForObject(
            "SELECT username FROM users WHERE id=? AND branch_id=?", String.class, id, branch);
    db.update("UPDATE users SET password_hash=? WHERE id=?", encoder.encode(change.password()), id);
    op.audit(branch, p.getName(), "PASSWORD_RESET", name);
    return Map.of("ok", true);
  }

  /** Cada persona puede cambiar su propia contraseña, tenga el rol que tenga. */
  @PostMapping("/password")
  @PreAuthorize("isAuthenticated()")
  @Transactional
  Object password(@Valid @RequestBody PasswordChange change, Principal p) {
    db.update(
        "UPDATE users SET password_hash=? WHERE username=?",
        encoder.encode(change.password()),
        p.getName());
    op.audit(op.branch(p.getName()), p.getName(), "PASSWORD_CHANGED", p.getName());
    return Map.of("ok", true);
  }

  private List<String> currentPermissions(long id) {
    return Permissions.normalize(
        new HashSet<>(
            db.queryForList(
                "SELECT permission FROM user_permissions WHERE user_id=?", String.class, id)));
  }

  private void grant(long id, Collection<String> keys) {
    for (String k : keys)
      db.update("INSERT INTO user_permissions(user_id,permission) VALUES (?,?)", id, k);
  }
}
