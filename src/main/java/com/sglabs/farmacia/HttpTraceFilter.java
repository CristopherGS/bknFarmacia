package com.sglabs.farmacia;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Logs every incoming request with route, status, duration, and response size. */
@Component
class HttpTraceFilter extends OncePerRequestFilter {
  private static final Logger LOG = LoggerFactory.getLogger(HttpTraceFilter.class);

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    long started = System.nanoTime();
    try {
      chain.doFilter(request, response);
    } catch (ServletException | IOException | RuntimeException e) {
      LOG.error(
          "HTTP entrante con excepción: método={}, ruta={}, host={}, duraciónMs={}, excepción={}",
          request.getMethod(),
          request.getRequestURI(),
          request.getServerName(),
          (System.nanoTime() - started) / 1_000_000,
          HttpTrace.exception(e),
          e);
      throw e;
    } finally {
      long elapsedMs = (System.nanoTime() - started) / 1_000_000;
      LOG.info(
          "HTTP entrante: método={}, ruta={}, host={}, status={}, duraciónMs={}, bytes={}",
          request.getMethod(),
          request.getRequestURI(),
          request.getServerName(),
          response.getStatus(),
          elapsedMs,
          response.getHeader("Content-Length"));
    }
  }
}
