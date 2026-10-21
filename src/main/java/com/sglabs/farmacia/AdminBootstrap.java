package com.sglabs.farmacia;

import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Initializes once after Flyway, before the web server starts accepting requests. */
@Component
@DependsOnDatabaseInitialization
public class AdminBootstrap implements InitializingBean {
  private final JdbcTemplate db;
  private final PasswordEncoder encoder;
  private final String password;
  private final TransactionTemplate transaction;

  public AdminBootstrap(
      JdbcTemplate db,
      PasswordEncoder encoder,
      @Value("${app.bootstrap-password:}") String password,
      PlatformTransactionManager transactions) {
    this.db = db;
    this.encoder = encoder;
    this.password = password;
    this.transaction = new TransactionTemplate(transactions);
  }

  @Override
  public void afterPropertiesSet() {
    transaction.executeWithoutResult(
        status -> {
          // Different replicas must not race to create the first account.
          db.queryForMap("SELECT id FROM branches WHERE id=1 FOR UPDATE");
          if (db.queryForObject("SELECT COUNT(*) FROM users", Integer.class) > 0) return;
          if (password.isBlank()
              || password.length() < 14
              || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalStateException(
                "BOOTSTRAP_ADMIN_PASSWORD es obligatoria para una base nueva: mínimo 14 caracteres"
                    + " y máximo 72 bytes UTF-8. Configúrela como variable de ejecución en Coolify."
                    + " No se creó ninguna cuenta.");
          }
          db.update(
              "INSERT INTO users(username,password_hash,role,branch_id) VALUES (?,?,?,1)",
              "admin",
              encoder.encode(password),
              "ADMIN");
          db.update(
              "INSERT INTO audit_logs(branch_id,actor,action,reference) VALUES"
                  + " (1,'SYSTEM','ADMIN_BOOTSTRAPPED','admin')");
        });
  }
}
