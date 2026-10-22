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
  SecurityFilterChain security(HttpSecurity http, JdbcTemplate db) throws Exception {
    var csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
    csrf.setCookieCustomizer(c -> c.sameSite("Strict").path("/"));
    return http.addFilterAfter(
            new ActiveUserFilter(db),
            org.springframework.security.web.context.SecurityContextHolderFilter.class)
        .csrf(
            c ->
                c.csrfTokenRepository(csrf)
                    .csrfTokenRequestHandler(
                        new org.springframework.security.web.csrf
                            .CsrfTokenRequestAttributeHandler()))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/actuator/health", "/actuator/health/**", "/api/v1/auth/csrf")
                    .permitAll()
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
