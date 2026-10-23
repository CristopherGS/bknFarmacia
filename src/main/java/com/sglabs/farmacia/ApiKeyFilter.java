package com.sglabs.farmacia;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Autentica a las cuentas de servicio que llegan con {@code Authorization: Bearer sgk_...}. */
class ApiKeyFilter extends OncePerRequestFilter {
  private final JdbcTemplate db;

  ApiKeyFilter(JdbcTemplate db) {
    this.db = db;
  }

  static boolean usesKey(HttpServletRequest request) {
    String h = request.getHeader("Authorization");
    return h != null && h.startsWith("Bearer " + IntegrationsController.PREFIX);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!usesKey(request)) {
      chain.doFilter(request, response);
      return;
    }
    String hash = IntegrationsController.hash(request.getHeader("Authorization").substring(7).trim());
    var user =
        db.queryForList(
            "SELECT u.username FROM api_keys k JOIN users u ON u.id=k.user_id WHERE k.key_hash=? AND"
                + " k.revoked_at IS NULL AND u.active",
            String.class,
            hash);
    if (user.isEmpty()) {
      response.sendError(401);
      return;
    }
    // Se anota el último uso como máximo una vez por minuto.
    db.update(
        "UPDATE api_keys SET last_used_at=CURRENT_TIMESTAMP WHERE key_hash=? AND (last_used_at IS"
            + " NULL OR last_used_at < CURRENT_TIMESTAMP - interval '1 minute')",
        hash);
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            user.getFirst(), null, List.of(new SimpleGrantedAuthority("ROLE_SERVICE"))));
    SecurityContextHolder.setContext(context);
    chain.doFilter(request, response);
  }
}
