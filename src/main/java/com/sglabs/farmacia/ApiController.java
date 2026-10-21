package com.sglabs.farmacia;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ApiController {
  final Operations op;
  final org.springframework.jdbc.core.JdbcTemplate db;

  public ApiController(Operations op, org.springframework.jdbc.core.JdbcTemplate db) {
    this.db = db;
    this.op = op;
  }

  @GetMapping("/auth/csrf")
  Map<String, String> csrf(CsrfToken token) {
    return Map.of("token", token.getToken());
  }

  @GetMapping("/auth/me")
  Object me(Principal p) {
    return op.user(p.getName());
  }

  @GetMapping("/products")
  Object products(Principal p) {
    return db.queryForList(
        "SELECT p.*,COALESCE((SELECT SUM(qty_base) FROM lots l WHERE l.product_id=p.id AND"
            + " l.branch_id=? AND state='AVAILABLE' AND expires_on>?),0) AS stock FROM products p"
            + " ORDER BY name",
        op.branch(p.getName()),
        java.time.LocalDate.now(java.time.ZoneId.of("America/Guatemala")));
  }

  @GetMapping("/presentations")
  Object presentations() {
    return db.queryForList(
        "SELECT pr.*,p.name AS product_name,p.generic_name,p.sku,p.requires_rx,p.controlled FROM"
            + " presentations pr"
            + " JOIN products p ON p.id=pr.product_id WHERE p.active=TRUE ORDER BY p.name,pr.factor"
            + " DESC");
  }

  @PostMapping("/products")
  @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
  Object product(@Valid @RequestBody Operations.Product product, Principal p) {
    return Map.of("id", op.product(product, p.getName()));
  }

  @GetMapping("/suppliers")
  Object suppliers() {
    return db.queryForList("SELECT * FROM suppliers ORDER BY name");
  }

  @PostMapping("/suppliers")
  @org.springframework.transaction.annotation.Transactional
  @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR','INVENTORY')")
  Object supplier(@Valid @RequestBody Operations.Supplier s, Principal p) {
    db.update(
        "INSERT INTO suppliers(name,nit,contact,lead_days) VALUES (?,?,?,?)",
        s.name(),
        Objects.toString(s.nit(), ""),
        Objects.toString(s.contact(), ""),
        s.leadDays());
    op.audit(op.branch(p.getName()), p.getName(), "SUPPLIER_CREATED", s.name());
    return Map.of("ok", true);
  }

  @PostMapping("/receipts")
  @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR','INVENTORY')")
  Object receive(@Valid @RequestBody Operations.Receipt r, Principal p) {
    return Map.of("id", op.receive(r, p.getName()));
  }

  @GetMapping("/receipts")
  @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR','INVENTORY','ACCOUNTANT')")
  Object receipts(Principal p) {
    return db.queryForList(
        "SELECT r.*,s.name AS supplier FROM receipts r JOIN suppliers s ON s.id=r.supplier_id WHERE"
            + " r.branch_id=? ORDER BY r.id DESC LIMIT 200",
        op.branch(p.getName()));
  }

  @GetMapping("/inventory")
  Object inventory(Principal p) {
    return db.queryForList(
        "SELECT l.*,p.name AS product_name,p.base_unit FROM lots l JOIN products p ON"
            + " p.id=l.product_id WHERE l.branch_id=? ORDER BY l.expires_on,l.id",
        op.branch(p.getName()));
  }

  @GetMapping("/movements")
  @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR','INVENTORY','ACCOUNTANT')")
  Object movements(Principal p) {
    return db.queryForList(
        "SELECT m.*,l.code,p.name AS product_name FROM stock_movements m JOIN lots l ON"
            + " l.id=m.lot_id JOIN products p ON p.id=l.product_id WHERE m.branch_id=? ORDER BY"
            + " m.id DESC LIMIT 500",
        op.branch(p.getName()));
  }

  @PostMapping("/sales/checkout")
  @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR','CASHIER')")
  Object checkout(
      @Valid @RequestBody Operations.Checkout c,
      @RequestHeader("Idempotency-Key") String key,
      Principal p) {
    return op.checkout(c, key, p.getName());
  }

  @GetMapping("/sales")
  @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR','CASHIER','ACCOUNTANT')")
  Object sales(Principal p) {
    return db.queryForList(
        "SELECT * FROM sales WHERE branch_id=? ORDER BY id DESC LIMIT 200", op.branch(p.getName()));
  }

  @GetMapping("/sales/{id}")
  @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR','CASHIER','ACCOUNTANT')")
  Object sale(@PathVariable long id, Principal p) {
    var sale =
        db.queryForMap(
            "SELECT * FROM sales WHERE id=? AND branch_id=?", id, op.branch(p.getName()));
    return Map.of(
        "sale",
        sale,
        "lines",
        db.queryForList("SELECT * FROM sale_lines WHERE sale_id=?", id),
        "notice",
        "Comprobante de control interno. No es factura FEL.");
  }

  @GetMapping("/cash")
  @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR','CASHIER','ACCOUNTANT')")
  Object cash(Principal p) {
    if (op.cashAutomationEnabled()) {
      op.synchronizeCash(p.getName());
      return db.queryForList(
          "SELECT * FROM cash_sessions WHERE branch_id=? ORDER BY id DESC LIMIT 50",
          op.branch(p.getName()));
    }
    return db.queryForList(
        "SELECT * FROM cash_sessions WHERE branch_id=? AND actor=? ORDER BY id DESC LIMIT 50",
        op.branch(p.getName()),
        p.getName());
  }

  @GetMapping("/cash/schedule")
  Object cashSchedule() {
    return Map.of(
        "automatic",
        op.cashAutomationEnabled(),
        "opensAt",
        "06:00",
        "closesAt",
        "00:00",
        "timezone",
        "America/Guatemala");
  }

  @PostMapping("/cash/open")
  @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR','CASHIER')")
  Object open(@Valid @RequestBody Operations.CashAmount c, Principal p) {
    return Map.of("id", op.open(c.amount(), p.getName()));
  }

  @PostMapping("/cash/close")
  @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR','CASHIER')")
  Object close(@Valid @RequestBody Operations.CashAmount c, Principal p) {
    return op.close(c.amount(), p.getName());
  }

  @GetMapping("/audit")
  @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR','ACCOUNTANT')")
  Object audit(Principal p) {
    return db.queryForList(
        "SELECT * FROM audit_logs WHERE branch_id=? ORDER BY id DESC LIMIT 500",
        op.branch(p.getName()));
  }
}
