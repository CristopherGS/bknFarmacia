package com.sglabs.farmacia;

import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
  @Bean
  PasswordEncoder encoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  UserDetailsService users(JdbcTemplate db) {
    return name ->
        db
            .query(
                "SELECT * FROM users WHERE username=? AND active=TRUE",
                (r, n) ->
                    User.withUsername(r.getString("username"))
                        .password(r.getString("password_hash"))
                        .roles(r.getString("role"))
                        .build(),
                name)
            .stream()
            .findFirst()
            .orElseThrow(() -> new UsernameNotFoundException("Credenciales incorrectas"));
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http, JdbcTemplate db, Perm perm) throws Exception {
    var csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
    csrf.setCookieCustomizer(c -> c.sameSite("Strict").path("/"));
    return http.addFilterAfter(
            new ApiKeyFilter(db),
            org.springframework.security.web.context.SecurityContextHolderFilter.class)
        .addFilterAfter(new ActiveUserFilter(db), ApiKeyFilter.class)
        .csrf(
            c ->
                // Las llaves de API van en un encabezado que un navegador no adjunta solo: no hay
                // riesgo CSRF, y un ataque con una llave inválida se rechaza con 401.
                c.ignoringRequestMatchers(ApiKeyFilter::usesKey)
                    .ignoringRequestMatchers("/api/v1/public/leads")
                    .csrfTokenRepository(csrf)
                    .csrfTokenRequestHandler(
                        new org.springframework.security.web.csrf
                            .CsrfTokenRequestAttributeHandler()))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/api/v1/public/leads",
                        "/actuator/health",
                        "/actuator/health/**",
                        "/api/v1/auth/csrf",
                        "/api/v1/settings/public",
                        // Tomcat redirige los errores (403, 404…) a /error; sin esto, quien se
                        // autentica con llave de API (sin sesión) vería 401 en lugar del 403 real.
                        "/error")
                    .permitAll()
                    // El portafolio personal se cierra desde la URL, antes de leer el cuerpo: quien no
                    // es el dueño recibe 403 aunque mande datos inválidos.
                    .requestMatchers("/api/v1/invest/**")
                    .access(
                        (auth, ctx) ->
                            new org.springframework.security.authorization.AuthorizationDecision(
                                auth.get().isAuthenticated() && perm.investOwner()))
                    .anyRequest()
                    .authenticated())
        .formLogin(
            f ->
                f.loginProcessingUrl("/api/v1/auth/login")
                    .successHandler(
                        (q, s, a) -> {
                          s.setContentType("application/json");
                          s.getWriter().write("{\"ok\":true}");
                        })
                    .failureHandler((q, s, e) -> s.sendError(401)))
        .logout(
            l ->
                l.logoutUrl("/api/v1/auth/logout")
                    .logoutSuccessHandler((q, s, a) -> s.setStatus(204)))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint((q, s, x) -> s.sendError(401))
                    .accessDeniedHandler((q, s, x) -> s.sendError(403)))
        .build();
  }
}
