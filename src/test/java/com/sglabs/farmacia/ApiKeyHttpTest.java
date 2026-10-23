package com.sglabs.farmacia;

import static org.assertj.core.api.Assertions.*;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Prueba por HTTP real: MockMvc no reproduce la redirección interna de Tomcat a /error, donde una
 * llave de API sin sesión podía convertir un 403 en un 401 engañoso.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"app.bootstrap-password=Test-only-password-8901", "app.cash.automatic=false"})
// Cierra el servidor web al terminar la clase; abierto hasta apagar la JVM retrasa el cierre.
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class ApiKeyHttpTest {
  static final EmbeddedPostgres pg;

  static {
    try {
      pg = EmbeddedPostgres.builder().setPort(0).start();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /** Apaga la base al terminar la clase: siete PostgreSQL abiertos hacen lento el cierre de la JVM. */
  @org.junit.jupiter.api.AfterAll
  static void closeDatabase() throws Exception {
    pg.close();
  }

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> pg.getJdbcUrl("postgres", "postgres"));
    r.add("spring.datasource.username", () -> "postgres");
    r.add("spring.datasource.password", () -> "");
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate db;
  final HttpClient http = HttpClient.newHttpClient();

  int get(String path, String bearer) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
    if (bearer != null) request.header("Authorization", "Bearer " + bearer);
    return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
  }

  @Test
  void aKeyWithoutPermissionGetsForbiddenNotUnauthorized() throws Exception {
    String key = "sgk_clave-de-prueba-http";
    long user =
        db.queryForObject(
            "INSERT INTO users(username,password_hash,role,branch_id) VALUES('svc-http','x','SERVICE',1) RETURNING id",
            Long.class);
    db.update("INSERT INTO user_permissions(user_id,permission) VALUES (?,'SALES_VIEW')", user);
    db.update(
        "INSERT INTO api_keys(user_id,label,prefix,key_hash,created_by) VALUES (?,?,?,?,?)",
        user, "http", key.substring(0, 12), IntegrationsController.hash(key), "test");

    assertThat(get("/api/v1/sales", key)).isEqualTo(200);
    assertThat(get("/api/v1/inventory", key)).isEqualTo(403); // llave válida, falta permiso
    assertThat(get("/api/v1/users", key)).isEqualTo(403);
    assertThat(get("/api/v1/sales", "sgk_inventada")).isEqualTo(401); // llave inválida
    assertThat(get("/api/v1/sales", null)).isEqualTo(401); // sin credenciales
    assertThat(get("/api/v1/settings/public", null)).isEqualTo(200);
  }
}
