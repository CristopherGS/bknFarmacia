package com.sglabs.farmacia;

import static org.assertj.core.api.Assertions.*;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Un negocio nuevo puede nacer ya configurado desde variables de entorno. */
@SpringBootTest(
    properties = {
      "app.bootstrap-password=Test-only-password-8901",
      "app.business.name=Tienda Luna",
      "app.business.type=tienda"
    })
class BusinessSetupTest {
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

  @Autowired Settings settings;

  @Test
  void newBusinessStartsWithItsOwnNameTypeAndModules() {
    var s = settings.get();
    assertThat(s.name()).isEqualTo("Tienda Luna");
    assertThat(s.type()).isEqualTo("TIENDA");
    assertThat(s.modules()).containsExactlyInAnyOrder("CASH", "AUDIT"); // sin el módulo de farmacia
  }

  @Test
  void upgradingKeepsEverythingAndDropsStaleQueuedEvents() throws Exception {
    try (var other = EmbeddedPostgres.builder().setPort(0).start()) {
      var ds = other.getPostgresDatabase();
      Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("4").load().migrate();
      var db = new JdbcTemplate(ds);
      db.update("INSERT INTO outbox_events(event_type,aggregate_id) VALUES ('sale.completed',1)");
      Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
      assertThat(db.queryForObject("SELECT business_name FROM settings", String.class)).isEqualTo("SG Farma");
      assertThat(db.queryForList("SELECT module FROM enabled_modules", String.class))
          .containsExactlyInAnyOrder("CASH", "AUDIT", "RX");
      assertThat(db.queryForObject("SELECT status FROM outbox_events", String.class)).isEqualTo("SKIPPED");
    }
  }
}
