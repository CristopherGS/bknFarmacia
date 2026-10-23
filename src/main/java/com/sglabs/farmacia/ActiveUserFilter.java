package com.sglabs.farmacia;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Cierra de inmediato la sesión de un usuario que el administrador desactivó. */
class ActiveUserFilter extends OncePerRequestFilter {
  private final JdbcTemplate db;

  ActiveUserFilter(JdbcTemplate db) {
    this.db = db;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
      boolean active =
          Boolean.TRUE.equals(
              db.queryForObject(
                  "SELECT EXISTS(SELECT 1 FROM users WHERE username=? AND active)",
                  Boolean.class,
                  auth.getName()));
      if (!active) {
        SecurityContextHolder.clearContext();
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        response.sendError(401);
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
