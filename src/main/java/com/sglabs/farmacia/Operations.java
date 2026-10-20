package com.sglabs.farmacia;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Operations {
  final JdbcTemplate db;
  final CashAutomation cashAutomation;
  final Clock clock;

  public Operations(JdbcTemplate db, CashAutomation cashAutomation, Clock clock) {
    this.db = db;
    this.cashAutomation = cashAutomation;
    this.clock = clock;
  }

  public record Product(
      @NotBlank String sku,
      @NotBlank String name,
      String genericName,
      @NotBlank String baseUnit,
      String category,
      boolean requiresRx,
      boolean controlled,
      boolean fractional,
      @Min(0) long minimumStock,
      @NotEmpty List<@Valid Presentation> presentations) {}

  public record Presentation(
      @NotBlank String name,
      @Min(1) long factor,
      @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal price,
      String barcode,
      boolean fractional) {}

  public record Supplier(@NotBlank String name, String nit, String contact, @Min(0) int leadDays) {}

  public record Receipt(
      @Min(1) long supplierId,
      @NotBlank String document,
      @NotEmpty List<@Valid ReceiptLine> lines) {}

  public record ReceiptLine(
      @Min(1) long presentationId,
      @Min(1) long quantity,
      @NotBlank String lot,
      @NotNull LocalDate expiresOn,
      @NotNull @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal totalCost) {}

  public record CartLine(
      @Min(1) long presentationId,
      @Min(1) long quantity,
      @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal expectedPrice) {}

  public record Checkout(
      @NotEmpty List<@Valid CartLine> lines,
      @Pattern(regexp = "CASH|CARD|TRANSFER") @NotNull String method,
      @NotNull @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal tendered,
      boolean prescriptionChecked) {}

  public record CashAmount(
      @NotNull @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal amount) {}

  Map<String, Object> user(String actor) {
    return db.queryForMap(
        "SELECT id,username,role,branch_id FROM users WHERE username=? AND active=TRUE", actor);
  }

  long branch(String actor) {
    return ((Number) user(actor).get("branch_id")).longValue();
  }

  long id() {
    return db.queryForObject("SELECT lastval()", Long.class);
  }

  public boolean cashAutomationEnabled() {
    return cashAutomation.enabled();
  }

  public void synchronizeCash(String actor) {
    cashAutomation.synchronizeBranch(branch(actor), clock.instant());
  }

  void audit(long branch, String actor, String action, String ref) {
    db.update(
        "INSERT INTO audit_logs(branch_id,actor,action,reference) VALUES (?,?,?,?)",
        branch,
        actor,
        action,
        ref);
  }

  @Transactional
  public long product(Product p, String actor) {
    db.update(
        "INSERT INTO"
            + " products(sku,name,generic_name,base_unit,category,requires_rx,controlled,fractional,minimum_stock)"
            + " VALUES (?,?,?,?,?,?,?,?,?)",
        p.sku().trim(),
        p.name().trim(),
        Objects.toString(p.genericName(), ""),
        p.baseUnit(),
        Objects.toString(p.category(), ""),
        p.requiresRx(),
        p.controlled(),
        p.fractional(),
        p.minimumStock());
    long product = id();
    for (var v : p.presentations()) {
      if (v.fractional() && !p.fractional())
        throw new IllegalArgumentException("Habilite fraccionamiento del producto primero");
      db.update(
          "INSERT INTO presentations(product_id,name,factor,price,barcode,fractional) VALUES"
              + " (?,?,?,?,?,?)",
          product,
          v.name(),
          v.factor(),
          v.price(),
          v.barcode() == null || v.barcode().isBlank() ? null : v.barcode().trim(),
          v.fractional());
    }
    audit(branch(actor), actor, "PRODUCT_CREATED", "" + product);
    return product;
  }

  @Transactional
  public long receive(Receipt r, String actor) {
    long b = branch(actor);
    db.update(
        "INSERT INTO receipts(branch_id,supplier_id,document,actor) VALUES (?,?,?,?)",
        b,
        r.supplierId(),
        r.document().trim(),
        actor);
    long receipt = id();
    for (var l : r.lines()) {
      if (!l.expiresOn().isAfter(LocalDate.now(ZoneId.of("America/Guatemala"))))
        throw new IllegalArgumentException("El lote debe vencer después de hoy");
      var p =
          db.queryForMap(
              "SELECT pr.* FROM presentations pr JOIN products p ON p.id=pr.product_id WHERE"
                  + " pr.id=? AND p.active=TRUE",
              l.presentationId());
      long units = Math.multiplyExact(l.quantity(), ((Number) p.get("factor")).longValue());
      db.update(
          "INSERT INTO lots(branch_id,product_id,receipt_id,code,expires_on,qty_base,unit_cost)"
              + " VALUES (?,?,?,?,?,?,?)",
          b,
          p.get("product_id"),
          receipt,
          l.lot().trim(),
          l.expiresOn(),
          units,
          l.totalCost().divide(BigDecimal.valueOf(units), 6, RoundingMode.HALF_UP));
      long lot = id();
      db.update(
          "INSERT INTO stock_movements(branch_id,lot_id,kind,delta_base,reference,actor,reason)"
              + " VALUES (?,?,'RECEIPT',?,?,?,?)",
          b,
          lot,
          units,
          "RECEIPT-" + receipt,
          actor,
          "Recepción " + r.document());
    }
    audit(b, actor, "RECEIPT_CREATED", "" + receipt);
    return receipt;
  }

  @Transactional
  public Map<String, Object> checkout(Checkout c, String key, String actor) {
    if (key == null || !key.matches("[a-zA-Z0-9-]{16,80}"))
      throw new IllegalArgumentException(
          "Se requiere Idempotency-Key estable (16 a 80 caracteres)");
    long b = branch(actor);
    // Serialize checkout and closure per branch. Re-read all balances after the lock.
    db.queryForMap("SELECT id FROM branches WHERE id=? FOR UPDATE", b);
    String hash;
    try {
      hash =
          HexFormat.of()
              .formatHex(
                  java.security.MessageDigest.getInstance("SHA-256")
                      .digest(
                          (actor + "|" + c.toString())
                              .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("No se pudo validar la solicitud");
    }
    var previous =
        db.queryForList("SELECT * FROM sales WHERE branch_id=? AND idempotency_key=?", b, key);
    if (!previous.isEmpty()) {
      if (!hash.equals(previous.getFirst().get("request_hash")))
        throw new IllegalStateException("La clave ya corresponde a otra solicitud");
      return previous.getFirst();
    }
    if (cashAutomation.enabled()) {
      Instant now = clock.instant();
      cashAutomation.synchronizeBranch(b, now);
      if (now.atZone(CashAutomation.ZONE).getHour() < 6)
        throw new IllegalStateException(
            "Caja cerrada por horario. Apertura automática a las 06:00.");
    }
    var sessions =
        db.queryForList(
            "SELECT id FROM cash_sessions WHERE branch_id=? AND actor=? AND closed_at IS NULL FOR"
                + " UPDATE",
            b,
            cashAutomation.enabled() ? "SYSTEM_CASH" : actor);
    if (sessions.isEmpty()) throw new IllegalStateException("Abra su caja antes de vender");
    var items = new ArrayList<Map<String, Object>>();
    BigDecimal total = BigDecimal.ZERO;
    for (var line : c.lines()) {
      var p =
          db.queryForMap(
              "SELECT pr.*,p.name AS product_name,p.requires_rx,p.controlled,p.fractional AS"
                  + " allow_fractional FROM presentations pr JOIN products p ON p.id=pr.product_id"
                  + " WHERE pr.id=? AND p.active=TRUE AND pr.sale_enabled=TRUE",
              line.presentationId());
      if (Boolean.TRUE.equals(p.get("controlled")))
        throw new IllegalStateException("Venta de controlados no habilitada");
      if (Boolean.TRUE.equals(p.get("requires_rx")) && !c.prescriptionChecked())
        throw new IllegalArgumentException("Confirme la revisión de receta");
      if (Boolean.TRUE.equals(p.get("fractional"))
          && !Boolean.TRUE.equals(p.get("allow_fractional")))
        throw new IllegalStateException("Fraccionamiento deshabilitado");
      BigDecimal price = (BigDecimal) p.get("price");
      if (price.compareTo(line.expectedPrice()) != 0)
        throw new IllegalStateException("El precio cambió. Actualice el carrito antes de cobrar");
      total = total.add(price.multiply(BigDecimal.valueOf(line.quantity())));
      items.add(p);
    }
    if (c.tendered().compareTo(total) < 0)
      throw new IllegalArgumentException("El pago no cubre el total");
    if (!c.method().equals("CASH") && c.tendered().compareTo(total) != 0)
      throw new IllegalArgumentException("El pago electrónico debe ser exacto");
    db.update(
        "INSERT INTO"
            + " sales(branch_id,cash_session_id,actor,idempotency_key,request_hash,total,method,tendered,change_amount)"
            + " VALUES (?,?,?,?,?,?,?,?,?)",
        b,
        sessions.getFirst().get("id"),
        actor,
        key,
        hash,
        total,
        c.method(),
        c.tendered(),
        c.tendered().subtract(total));
    long sale = id();
    for (int i = 0; i < items.size(); i++) {
      var p = items.get(i);
      var line = c.lines().get(i);
      long remaining = Math.multiplyExact(line.quantity(), ((Number) p.get("factor")).longValue());
      db.update(
          "INSERT INTO"
              + " sale_lines(sale_id,presentation_id,product_name,presentation_name,quantity,factor,price)"
              + " VALUES (?,?,?,?,?,?,?)",
          sale,
          line.presentationId(),
          p.get("product_name"),
          p.get("name"),
          line.quantity(),
          p.get("factor"),
          p.get("price"));
      long lineId = id();
      var lots =
          db.queryForList(
              "SELECT * FROM lots WHERE branch_id=? AND product_id=? AND state='AVAILABLE' AND"
                  + " qty_base>0 AND expires_on>? ORDER BY expires_on,received_at,id FOR UPDATE",
              b,
              p.get("product_id"),
              LocalDate.now(ZoneId.of("America/Guatemala")));
      for (var lot : lots) {
        if (remaining == 0) break;
        long take = Math.min(remaining, ((Number) lot.get("qty_base")).longValue());
        db.update("UPDATE lots SET qty_base=qty_base-? WHERE id=?", take, lot.get("id"));
        db.update(
            "INSERT INTO allocations(line_id,lot_id,quantity,cost) VALUES (?,?,?,?)",
            lineId,
            lot.get("id"),
            take,
            lot.get("unit_cost"));
        db.update(
            "INSERT INTO stock_movements(branch_id,lot_id,kind,delta_base,reference,actor,reason)"
                + " VALUES (?,?,'SALE',?,?,?,'Venta de control interno')",
            b,
            lot.get("id"),
            -take,
            "SALE-" + sale,
            actor);
        remaining -= take;
      }
      if (remaining > 0)
        throw new IllegalStateException("Stock insuficiente: " + p.get("product_name"));
    }
    audit(b, actor, "SALE_COMPLETED", "" + sale);
    db.update(
        "INSERT INTO outbox_events(event_type,aggregate_id) VALUES ('sale.completed',?)", sale);
    return db.queryForMap("SELECT * FROM sales WHERE id=?", sale);
  }

  @Transactional
  public long open(BigDecimal amount, String actor) {
    if (cashAutomation.enabled())
      throw new IllegalStateException("La caja se abre automáticamente a las 06:00.");
    long b = branch(actor);
    db.queryForMap("SELECT id FROM branches WHERE id=? FOR UPDATE", b);
    db.update(
        "INSERT INTO cash_sessions(branch_id,actor,opening) VALUES (?,?,?)", b, actor, amount);
    long result = id();
    audit(b, actor, "CASH_OPENED", "" + result);
    return result;
  }

  @Transactional
  public Map<String, Object> close(BigDecimal amount, String actor) {
    if (cashAutomation.enabled())
      throw new IllegalStateException("La caja se cierra automáticamente a medianoche.");
    long b = branch(actor);
    db.queryForMap("SELECT id FROM branches WHERE id=? FOR UPDATE", b);
    var s =
        db.queryForList(
            "SELECT * FROM cash_sessions WHERE branch_id=? AND actor=? AND closed_at IS NULL FOR"
                + " UPDATE",
            b,
            actor);
    if (s.isEmpty()) throw new IllegalStateException("No hay caja abierta");
    long id = ((Number) s.getFirst().get("id")).longValue();
    BigDecimal cash =
        db.queryForObject(
            "SELECT COALESCE(SUM(total),0) FROM sales WHERE cash_session_id=? AND method='CASH'",
            BigDecimal.class,
            id);
    BigDecimal expected = ((BigDecimal) s.getFirst().get("opening")).add(cash);
    db.update(
        "UPDATE cash_sessions SET closed_at=CURRENT_TIMESTAMP,counted=?,expected=?,difference=?"
            + " WHERE id=?",
        amount,
        expected,
        amount.subtract(expected),
        id);
    audit(b, actor, "CASH_CLOSED", "" + id);
    return db.queryForMap("SELECT * FROM cash_sessions WHERE id=?", id);
  }
}
