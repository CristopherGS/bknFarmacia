package com.sglabs.farmacia;

import static org.assertj.core.api.Assertions.*;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(OutputCaptureExtension.class)
class StartupTest {
  static final String PASSWORD = "Disposable-test-password-12345";
  static EmbeddedPostgres pg;

  @BeforeAll
  static void startPostgres() throws Exception {
    pg = EmbeddedPostgres.builder().setPort(0).start();
  }

  @AfterAll
  static void stopPostgres() throws Exception {
    pg.close();
  }

  String schema() throws Exception {
    String name = "boot_" + UUID.randomUUID().toString().replace("-", "");
    try (var c = pg.getPostgresDatabase().getConnection();
        var s = c.createStatement()) {
      s.execute("CREATE SCHEMA " + name);
    }
    return name;
  }

  ConfigurableApplicationContext boot(
      String schema, String password, List<WebServerInitializedEvent> events) {
    return new SpringApplicationBuilder(Application.class)
        .listeners((ApplicationListener<WebServerInitializedEvent>) events::add)
        .run(
            "--spring.datasource.url="
                + pg.getJdbcUrl("postgres", "postgres")
                + "&currentSchema="
                + schema,
            "--spring.datasource.username=postgres",
            "--spring.datasource.password=",
            "--app.bootstrap-password=" + password,
            "--app.cash.automatic=false",
            "--server.port=0",
            "--server.address=127.0.0.1",
            "--server.servlet.session.cookie.secure=false",
            "--spring.main.banner-mode=off");
  }

  /** Cuántas migraciones trae la aplicación; así la prueba no se rompe cada vez que se agrega una. */
  static int migrationFiles() throws Exception {
    return new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
        .getResources("classpath:db/migration/V*.sql")
        .length;
  }

  @Test
  void missingInitialPasswordFailsBeforeWebServerAcceptsTraffic(CapturedOutput output)
      throws Exception {
    var events = new CopyOnWriteArrayList<WebServerInitializedEvent>();
    assertThatThrownBy(() -> boot(schema(), "", events))
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .rootCause()
        .hasMessageContaining("BOOTSTRAP_ADMIN_PASSWORD");
    assertThat(events).isEmpty();
    assertThat(output).doesNotContain(PASSWORD);
  }

  @Test
  void startupMigratesPostgres18AndSurvivesRestartWithoutResettingAdmin(CapturedOutput output)
      throws Exception {
    String schema = schema();
    var events = new CopyOnWriteArrayList<WebServerInitializedEvent>();
    try (var context = boot(schema, PASSWORD, events)) {
      var db = context.getBean(JdbcTemplate.class);
      assertThat(db.queryForObject("SHOW server_version", String.class)).startsWith("18.");
      assertThat(
              db.queryForObject(
                  "SELECT COUNT(*) FROM flyway_schema_history WHERE success=TRUE", Integer.class))
          .isEqualTo(migrationFiles());
      var encoder = context.getBean(PasswordEncoder.class);
      assertThat(
              encoder.matches(
                  PASSWORD,
                  db.queryForObject(
                      "SELECT password_hash FROM users WHERE username='admin'", String.class)))
          .isTrue();
      String base =
          "http://127.0.0.1:"
              + ((ServletWebServerApplicationContext) context).getWebServer().getPort();
      var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
      var client =
          HttpClient.newBuilder()
              .cookieHandler(cookies)
              .connectTimeout(Duration.ofSeconds(3))
              .build();
      for (String path : List.of("/actuator/health", "/actuator/health/readiness")) {
        var health =
            client.send(
                HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"UP\"");
      }
      var json = context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class);
      var csrf =
          client.send(
              HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/csrf")).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      String token = json.readTree(csrf.body()).get("token").asText();
      var login =
          client.send(
              HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/login"))
                  .header("Content-Type", "application/x-www-form-urlencoded")
                  .header("X-XSRF-TOKEN", token)
                  .POST(HttpRequest.BodyPublishers.ofString("username=admin&password=" + PASSWORD))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(login.statusCode()).isEqualTo(200);
      var me =
          client.send(
              HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/me")).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(me.statusCode()).isEqualTo(200);
      assertThat(me.body()).contains("admin");
      var forwarded =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/csrf"))
                      .header("X-Forwarded-Proto", "https")
                      .header("X-Forwarded-Host", "med.sglabs.lat")
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
      assertThat(forwarded.statusCode()).isEqualTo(200);
      assertThat(forwarded.headers().allValues("Set-Cookie"))
          .anyMatch(cookie -> cookie.contains("XSRF-TOKEN=") && cookie.contains("Secure"));
      assertThat(context.isActive()).isTrue();
      assertThat(events).hasSize(1);
    }
    // A deployed instance with existing accounts must not require or change the bootstrap secret.
    try (var restarted = boot(schema, "", new CopyOnWriteArrayList<>())) {
      var db = restarted.getBean(JdbcTemplate.class);
      assertThat(db.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(1);
      assertThat(
              restarted
                  .getBean(PasswordEncoder.class)
                  .matches(
                      PASSWORD,
                      db.queryForObject(
                          "SELECT password_hash FROM users WHERE username='admin'", String.class)))
          .isTrue();
      assertThat(restarted.isActive()).isTrue();
    }
    assertThat(output).doesNotContain(PASSWORD);
  }
}
