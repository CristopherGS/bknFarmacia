package com.sglabs.farmacia;

import static org.assertj.core.api.Assertions.*;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** La migración V4 debe dar a los usuarios ya existentes lo mismo que las plantillas de Permissions. */
class PermissionMigrationTest {
  @Test
  void existingUsersKeepWhatTheirRoleAllowed() throws Exception {
    try (var pg = EmbeddedPostgres.builder().setPort(0).start()) {
      var ds = pg.getPostgresDatabase();
      Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("3").load().migrate();
      var db = new JdbcTemplate(ds);
      for (String role : Permissions.ROLES)
        db.update(
            "INSERT INTO users(username,password_hash,role,branch_id) VALUES(?,?,?,1)",
            "u-" + role.toLowerCase(),
            "hash",
            role);
      Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
      for (String role : Permissions.ROLES) {
        var granted =
            new TreeSet<>(
                db.queryForList(
                    "SELECT permission FROM user_permissions p JOIN users u ON u.id=p.user_id WHERE"
                        + " u.username=?",
                    String.class,
                    "u-" + role.toLowerCase()));
        var expected = role.equals("ADMIN") ? new TreeSet<String>() : new TreeSet<>(Permissions.preset(role));
        assertThat(granted).as("permisos sembrados para " + role).isEqualTo(expected);
      }
    }
  }
}
