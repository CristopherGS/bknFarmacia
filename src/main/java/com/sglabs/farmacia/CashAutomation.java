package com.sglabs.farmacia;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CashAutomation {
  public static final ZoneId ZONE = ZoneId.of("America/Guatemala");
  private final JdbcTemplate db;
  private final boolean enabled;
  private final BigDecimal opening;
  private Events events;

  /** Spring lo inyecta; con instancias manuales (pruebas) no se emiten eventos. */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void events(Events events) {
    this.events = events;
  }

  public CashAutomation(
      JdbcTemplate db,
      @Value("${app.cash.automatic:true}") boolean enabled,
      @Value("${app.cash.opening-amount:0.00}") BigDecimal opening) {
    if (opening.signum() < 0 || opening.scale() > 2)
      throw new IllegalArgumentException("Fondo de caja inválido");
    this.db = db;
    this.enabled = enabled;
    this.opening = opening;
  }

  public boolean enabled() {
    return enabled;
  }

  @Transactional
  public void synchronizeBranch(long branch, Instant instant) {
    if (!enabled) return;
    db.queryForMap("SELECT id FROM branches WHERE id=? FOR UPDATE", branch);
    var local = instant.atZone(ZONE);
    var day = local.toLocalDate();
    // Recover a missed midnight, including sessions created before automation was enabled.
    var stale =
        db.queryForList(
            "SELECT * FROM cash_sessions WHERE branch_id=? AND closed_at IS NULL AND"
                + " (automatic=FALSE OR business_date<?) ORDER BY id FOR UPDATE",
            branch,
            day);
    for (var session : stale) {
      long id = ((Number) session.get("id")).longValue();
      Instant cutoff = instant;
      if (Boolean.TRUE.equals(session.get("automatic")))
        cutoff =
            ((java.sql.Date) session.get("business_date"))
                .toLocalDate()
                .plusDays(1)
                .atStartOfDay(ZONE)
                .toInstant();
      var cash =
          db.queryForObject(
              "SELECT COALESCE(SUM(total),0) FROM sales WHERE cash_session_id=? AND method='CASH'",
              BigDecimal.class,
              id);
      var expected = ((BigDecimal) session.get("opening")).add(cash);
      db.update(
          "UPDATE cash_sessions SET"
              + " closed_at=?,expected=?,counted=NULL,difference=NULL,close_mode='AUTOMATIC' WHERE"
              + " id=?",
          Timestamp.from(cutoff),
          expected,
          id);
      audit(branch, "CASH_AUTO_CLOSED", id);
      if (events != null && events.enabled())
        events.emit(
            "cash.closed",
            id,
            java.util.Map.of(
                "sessionId", id,
                "mode", "AUTOMATIC",
                "opening", session.get("opening"),
                "expected", expected));
    }
    if (local.toLocalTime().isBefore(LocalTime.of(6, 0))) return;
    var exists =
        db.queryForList(
            "SELECT id FROM cash_sessions WHERE branch_id=? AND business_date=? AND automatic=TRUE",
            branch,
            day);
    if (exists.isEmpty()) {
      long id =
          db.queryForObject(
              "INSERT INTO cash_sessions(branch_id,actor,opening,opened_at,automatic,business_date)"
                  + " VALUES (?,'SYSTEM_CASH',?,?,TRUE,?) RETURNING id",
              Long.class,
              branch,
              opening,
              Timestamp.from(day.atTime(6, 0).atZone(ZONE).toInstant()),
              day);
      audit(branch, "CASH_AUTO_OPENED", id);
    }
  }

  private void audit(long branch, String action, long id) {
    db.update(
        "INSERT INTO audit_logs(branch_id,actor,action,reference) VALUES (?,'SYSTEM_CASH',?,?)",
        branch,
        action,
        Long.toString(id));
  }
}
