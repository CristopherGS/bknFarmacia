package com.sglabs.farmacia;

import java.time.LocalDate;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Eventos que no nacen de una acción de una persona sino del paso del tiempo. */
@Component
public class EventJobs {
  private static final Logger LOG = LoggerFactory.getLogger(EventJobs.class);
  /** Avisos de vencimiento: a 90 y a 30 días. */
  static final List<Integer> THRESHOLDS = List.of(90, 30);

  private final JdbcTemplate db;
  private final Events events;

  public EventJobs(JdbcTemplate db, Events events) {
    this.db = db;
    this.events = events;
  }

  @Scheduled(cron = "0 0 7 * * *", zone = "America/Guatemala")
  public void daily() {
    try {
      checkExpiring();
    } catch (RuntimeException e) {
      LOG.error("No se pudo revisar los vencimientos", e);
    }
    try {
      purgeOldInvoiceFiles();
    } catch (RuntimeException e) {
      LOG.error("No se pudo limpiar los archivos de facturas", e);
    }
  }

  /** Las imágenes de facturas ya cerradas se borran a los 30 días; el borrador y su historial quedan. */
  public int purgeOldInvoiceFiles() {
    return db.update(
        "DELETE FROM invoice_files WHERE draft_id IN (SELECT id FROM receipt_drafts WHERE status IN"
            + " ('CONFIRMED','DISCARDED','FAILED') AND updated_at < CURRENT_TIMESTAMP - interval '30"
            + " days')");
  }

  /**
   * Emite un evento lot.expiring por sucursal y umbral con los lotes que acaban de entrar en la
   * ventana y todavía no se habían avisado. Devuelve cuántos lotes se avisaron.
   */
  @Transactional
  public int checkExpiring() {
    if (!events.enabled()) return 0;
    var today = LocalDate.now(CashAutomation.ZONE);
    int total = 0;
    // Del umbral más corto al más largo: un lote a 20 días se avisa como "30" y no también como "90".
    var alreadyCovered = new HashSet<Long>();
    for (int days : THRESHOLDS.stream().sorted().toList()) {
      var lots =
          db.queryForList(
              "SELECT l.id,l.branch_id,l.code,l.expires_on,l.qty_base,p.name AS product_name,p.sku"
                  + " FROM lots l JOIN products p ON p.id=l.product_id WHERE l.qty_base>0 AND"
                  + " l.state='AVAILABLE' AND l.expires_on>? AND l.expires_on<=? AND NOT EXISTS"
                  + " (SELECT 1 FROM expiry_alerts a WHERE a.lot_id=l.id AND a.threshold_days<=?)"
                  + " ORDER BY l.branch_id,l.expires_on,l.id",
              today,
              today.plusDays(days),
              days);
      var byBranch = new LinkedHashMap<Long, List<Map<String, Object>>>();
      for (var l : lots) {
        long id = ((Number) l.get("id")).longValue();
        if (!alreadyCovered.add(id)) continue;
        long branch = ((Number) l.get("branch_id")).longValue();
        var expires = ((java.sql.Date) l.get("expires_on")).toLocalDate();
        var item = new LinkedHashMap<String, Object>();
        item.put("lotId", id);
        item.put("product", l.get("product_name"));
        item.put("sku", l.get("sku"));
        item.put("lot", l.get("code"));
        item.put("expiresOn", expires.toString());
        item.put("daysLeft", java.time.temporal.ChronoUnit.DAYS.between(today, expires));
        item.put("units", l.get("qty_base"));
        byBranch.computeIfAbsent(branch, k -> new ArrayList<>()).add(item);
        db.update(
            "INSERT INTO expiry_alerts(lot_id,threshold_days) VALUES (?,?) ON CONFLICT DO NOTHING",
            id,
            days);
        total++;
      }
      for (var e : byBranch.entrySet())
        events.emit(
            "lot.expiring", e.getKey(), Map.of("thresholdDays", days, "lots", e.getValue()));
    }
    return total;
  }
}
