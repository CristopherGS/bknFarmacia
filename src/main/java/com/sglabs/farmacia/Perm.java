package com.sglabs.farmacia;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Autorización por permisos, usada como {@code @PreAuthorize("@perm.has('POS_SELL')")}. Se consulta
 * la base en cada petición, de modo que un cambio del administrador surte efecto de inmediato, sin
 * esperar a que la persona cierre sesión.
 */
@Component("perm")
public class Perm {
  private final JdbcTemplate db;
  private final Settings settings;
  private final String investOwner;

  public Perm(
      JdbcTemplate db,
      Settings settings,
      @org.springframework.beans.factory.annotation.Value("${app.invest.owner:}") String investOwner) {
    this.db = db;
    this.settings = settings;
    this.investOwner = investOwner == null ? "" : investOwner.trim();
  }

  /**
   * El apartado de inversiones personales es solo del dueño: debe estar configurada la variable
   * INVEST_OWNER, coincidir con tu usuario, y ser administrador activo. Sin la variable, nadie entra.
   */
  public boolean investOwner() {
    String name = current();
    return !investOwner.isEmpty()
        && investOwner.equals(name)
        && Boolean.TRUE.equals(
            db.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM users WHERE username=? AND active AND role='ADMIN')",
                Boolean.class,
                name));
  }

  public boolean has(String key) {
    return any(key);
  }

  /** Verdadero si el usuario activo tiene al menos uno de los permisos (ADMIN los tiene todos). */
  public boolean any(String... keys) {
    String name = current();
    if (name == null) return false;
    // Un módulo apagado deja sin efecto sus permisos, también para el administrador.
    var modules = settings.get().modules();
    keys =
        Arrays.stream(keys)
            .filter(k -> Permissions.moduleOf(k) == null || modules.contains(Permissions.moduleOf(k)))
            .toArray(String[]::new);
    if (keys.length == 0) return false;
    var args = new ArrayList<Object>();
    args.add(name);
    args.addAll(Arrays.asList(keys));
    return Boolean.TRUE.equals(
        db.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM users u WHERE u.username=? AND u.active AND (u.role='ADMIN'"
                + " OR EXISTS(SELECT 1 FROM user_permissions p WHERE p.user_id=u.id AND p.permission"
                + " IN ("
                + String.join(",", Collections.nCopies(keys.length, "?"))
                + "))))",
            Boolean.class,
            args.toArray()));
  }

  public boolean admin() {
    String name = current();
    return name != null
        && Boolean.TRUE.equals(
            db.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM users WHERE username=? AND active AND role='ADMIN')",
                Boolean.class,
                name));
  }

  /** Permisos efectivos de un usuario (todos si es ADMIN). */
  public List<String> of(String username) {
    var role =
        db.queryForList("SELECT role FROM users WHERE username=? AND active", String.class, username);
    if (role.isEmpty()) return List.of();
    var modules = settings.get().modules();
    java.util.function.Predicate<String> usable =
        k -> Permissions.moduleOf(k) == null || modules.contains(Permissions.moduleOf(k));
    if (role.getFirst().equals("ADMIN")) return Permissions.KEYS.stream().filter(usable).toList();
    var granted =
        new HashSet<>(
            db.queryForList(
                "SELECT p.permission FROM user_permissions p JOIN users u ON u.id=p.user_id WHERE"
                    + " u.username=?",
                String.class,
                username));
    return Permissions.KEYS.stream().filter(granted::contains).filter(usable).toList();
  }

  private static String current() {
    Authentication a = SecurityContextHolder.getContext().getAuthentication();
    if (a == null || !a.isAuthenticated() || a instanceof AnonymousAuthenticationToken) return null;
    return a.getName();
  }
}
