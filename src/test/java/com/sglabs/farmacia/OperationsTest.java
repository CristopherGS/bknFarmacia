package com.sglabs.farmacia;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "app.bootstrap-password=Test-only-password-8901",
      "server.servlet.session.cookie.secure=false",
      "app.cash.automatic=false"
    })
@AutoConfigureMockMvc
class OperationsTest {
  static final EmbeddedPostgres pg;

  static {
    try {
      pg = EmbeddedPostgres.builder().setPort(0).start();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> pg.getJdbcUrl("postgres", "postgres"));
    r.add("spring.datasource.username", () -> "postgres");
    r.add("spring.datasource.password", () -> "");
  }

  @Autowired Operations op;
  @Autowired JdbcTemplate db;
  @Autowired MockMvc mvc;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
  long box, blister, tablet, supplier;

  @BeforeEach
  void setup() {
    db.execute(
        "TRUNCATE"
            + " outbox_events,audit_logs,stock_movements,allocations,sale_lines,sales,cash_sessions,lots,receipts,presentations,products,suppliers"
            + " RESTART IDENTITY CASCADE");
    op.product(
        new Operations.Product(
            "DEMO",
            "Producto de prueba",
            "Genérico",
            "tableta",
            "Prueba",
            false,
            false,
            true,
            10,
            List.of(
                new Operations.Presentation("Caja", 20, new BigDecimal("40.00"), "BOX", false),
                new Operations.Presentation(
                    "Blíster", 10, new BigDecimal("22.00"), "BLISTER", true),
                new Operations.Presentation("Tableta", 1, new BigDecimal("2.50"), "UNIT", true))),
        "admin");
    box = 1;
    blister = 2;
    tablet = 3;
    db.update("INSERT INTO suppliers(name) VALUES ('Proveedor ficticio')");
    supplier = 1;
  }

  void receive(long qty, String code, LocalDate expiry) {
    op.receive(
        new Operations.Receipt(
            supplier,
            UUID.randomUUID().toString(),
            List.of(new Operations.ReceiptLine(box, qty, code, expiry, new BigDecimal("30.00")))),
        "admin");
  }

  Operations.Checkout checkout(long presentation, long qty, String price, String total) {
    return new Operations.Checkout(
        List.of(new Operations.CartLine(presentation, qty, new BigDecimal(price))),
        "CASH",
        new BigDecimal(total),
        false);
  }

  long quantity() {
    return db.queryForObject("SELECT COALESCE(SUM(qty_base),0) FROM lots", Long.class);
  }

  @Test
  void threeBoxesAreSixtyBaseUnitsAndAudited() {
    receive(3, "LOT-A", LocalDate.now().plusYears(1));
    assertThat(quantity()).isEqualTo(60);
    assertThat(db.queryForObject("SELECT SUM(delta_base) FROM stock_movements", Long.class))
        .isEqualTo(60);
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action='RECEIPT_CREATED'", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void mixedPresentationsConsumeThirtyThreeAndChargeSixtyNineFifty() {
    receive(3, "LOT-A", LocalDate.now().plusYears(1));
    op.open(BigDecimal.ZERO, "admin");
    var c =
        new Operations.Checkout(
            List.of(
                new Operations.CartLine(box, 1, new BigDecimal("40")),
                new Operations.CartLine(blister, 1, new BigDecimal("22")),
                new Operations.CartLine(tablet, 3, new BigDecimal("2.5"))),
            "CASH",
            new BigDecimal("70"),
            false);
    var s = op.checkout(c, UUID.randomUUID().toString(), "admin");
    assertThat(quantity()).isEqualTo(27);
    assertThat((BigDecimal) s.get("total")).isEqualByComparingTo("69.50");
    assertThat((BigDecimal) s.get("change_amount")).isEqualByComparingTo("0.50");
  }

  @Test
  void retryDoesNotDoubleChargeAndPayloadCannotChange() {
    receive(1, "LOT-A", LocalDate.now().plusYears(1));
    op.open(BigDecimal.ZERO, "admin");
    var key = UUID.randomUUID().toString();
    var c = checkout(box, 1, "40", "40");
    var a = op.checkout(c, key, "admin");
    var b = op.checkout(c, key, "admin");
    assertThat(a.get("id")).isEqualTo(b.get("id"));
    assertThat(quantity()).isZero();
    assertThatThrownBy(() -> op.checkout(checkout(tablet, 1, "2.50", "2.50"), key, "admin"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void insufficientStockRollsBackSaleAndMovements() {
    receive(1, "LOT-A", LocalDate.now().plusYears(1));
    op.open(BigDecimal.ZERO, "admin");
    assertThatThrownBy(
            () -> op.checkout(checkout(box, 2, "40", "80"), UUID.randomUUID().toString(), "admin"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(quantity()).isEqualTo(20);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM sales", Integer.class)).isZero();
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM stock_movements WHERE kind='SALE'", Integer.class))
        .isZero();
  }

  @Test
  void expiredAndBlockedLotsCannotBeSold() {
    receive(1, "LOT-A", LocalDate.now().plusYears(1));
    op.open(BigDecimal.ZERO, "admin");
    db.update("UPDATE lots SET expires_on=?", LocalDate.now().minusDays(1));
    assertThatThrownBy(
            () ->
                op.checkout(
                    checkout(tablet, 1, "2.50", "2.50"), UUID.randomUUID().toString(), "admin"))
        .isInstanceOf(IllegalStateException.class);
    db.update("UPDATE lots SET expires_on=?,state='QUARANTINE'", LocalDate.now().plusYears(1));
    assertThatThrownBy(
            () ->
                op.checkout(
                    checkout(tablet, 1, "2.50", "2.50"), UUID.randomUUID().toString(), "admin"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void earliestExpiryIsConsumedFirst() {
    receive(1, "LATER", LocalDate.now().plusYears(2));
    receive(1, "EARLIER", LocalDate.now().plusYears(1));
    op.open(BigDecimal.ZERO, "admin");
    op.checkout(checkout(tablet, 5, "2.50", "12.50"), UUID.randomUUID().toString(), "admin");
    assertThat(db.queryForObject("SELECT qty_base FROM lots WHERE code='EARLIER'", Long.class))
        .isEqualTo(15);
    assertThat(db.queryForObject("SELECT qty_base FROM lots WHERE code='LATER'", Long.class))
        .isEqualTo(20);
  }

  @Test
  void concurrentCashiersCannotOversell() throws Exception {
    receive(1, "LAST", LocalDate.now().plusYears(1));
    op.open(BigDecimal.ZERO, "admin");
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<Boolean> job =
          () -> {
            start.await();
            try {
              op.checkout(checkout(box, 1, "40", "40"), UUID.randomUUID().toString(), "admin");
              return true;
            } catch (IllegalStateException ex) {
              return false;
            }
          };
      var a = pool.submit(job);
      var b = pool.submit(job);
      start.countDown();
      assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(true, false);
      assertThat(quantity()).isZero();
      assertThat(db.queryForObject("SELECT COUNT(*) FROM sales", Integer.class)).isEqualTo(1);
    }
  }

  @Test
  void duplicateReceiptDoesNotIncreaseStock() {
    var r =
        new Operations.Receipt(
            1,
            "DUPLICATE",
            List.of(
                new Operations.ReceiptLine(
                    box, 3, "A", LocalDate.now().plusYears(1), new BigDecimal("30"))));
    op.receive(r, "admin");
    assertThatThrownBy(() -> op.receive(r, "admin"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThat(quantity()).isEqualTo(60);
  }

  @Test
  void closeReconcilesCashAndRejectsFurtherSales() {
    receive(3, "LOT-A", LocalDate.now().plusYears(1));
    op.open(new BigDecimal("100"), "admin");
    op.checkout(checkout(box, 1, "40", "50"), UUID.randomUUID().toString(), "admin");
    var result = op.close(new BigDecimal("140"), "admin");
    assertThat((BigDecimal) result.get("difference")).isEqualByComparingTo("0");
    assertThatThrownBy(
            () -> op.checkout(checkout(box, 1, "40", "40"), UUID.randomUUID().toString(), "admin"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void changedPriceRejected() {
    receive(1, "LOT-A", LocalDate.now().plusYears(1));
    op.open(BigDecimal.ZERO, "admin");
    assertThatThrownBy(
            () ->
                op.checkout(checkout(box, 1, "0.01", "40"), UUID.randomUUID().toString(), "admin"))
        .hasMessageContaining("precio cambió");
    assertThat(quantity()).isEqualTo(20);
  }

  @Test
  void cashierCannotCreateProductsAndCsrfIsRequired() throws Exception {
    mvc.perform(
            post("/api/v1/products")
                .with(user("admin").roles("CASHIER"))
                .with(csrf())
                .contentType("application/json")
                .content(
                    "{\"sku\":\"X\",\"name\":\"X\",\"baseUnit\":\"X\",\"minimumStock\":0,\"presentations\":[{\"name\":\"X\",\"factor\":1,\"price\":1}]}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/cash/open")
                .with(user("admin").roles("ADMIN"))
                .contentType("application/json")
                .content("{\"amount\":0}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void historicalMovementsCannotBeRewritten() {
    receive(1, "AUDIT", LocalDate.now().plusYears(1));
    assertThatThrownBy(() -> db.update("UPDATE stock_movements SET delta_base=999"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(() -> db.update("DELETE FROM audit_logs"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void loginCreatesSessionAndLogoutRevokesIt() throws Exception {
    mvc.perform(get("/api/v1/products")).andExpect(status().isUnauthorized());
    var login =
        mvc.perform(
                post("/api/v1/auth/login")
                    .with(csrf())
                    .param("username", "admin")
                    .param("password", "Test-only-password-8901"))
            .andExpect(status().isOk())
            .andReturn();
    var session =
        (org.springframework.mock.web.MockHttpSession) login.getRequest().getSession(false);
    mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isOk());
    mvc.perform(post("/api/v1/auth/logout").session(session).with(csrf()))
        .andExpect(status().isNoContent());
    assertThat(session.isInvalid()).isTrue();
  }

  @Test
  void branchCannotReadOtherBranchSale() throws Exception {
    receive(1, "PRIVATE", LocalDate.now().plusYears(1));
    op.open(BigDecimal.ZERO, "admin");
    var sale =
        op.checkout(checkout(tablet, 1, "2.50", "2.50"), UUID.randomUUID().toString(), "admin");
    db.update("INSERT INTO branches(id,name) VALUES(2,'Prueba aislada') ON CONFLICT DO NOTHING");
    db.update(
        "INSERT INTO users(username,password_hash,role,branch_id) VALUES('other',?,'CASHIER',2) ON"
            + " CONFLICT DO NOTHING",
        "unused-test-hash");
    mvc.perform(get("/api/v1/sales/" + sale.get("id")).with(user("other").roles("CASHIER")))
        .andExpect(status().isNotFound());
  }

  @Test
  void allInitialReadEndpointsAreAccessibleToAdministrator() throws Exception {
    for (String resource :
        List.of(
            "products",
            "presentations",
            "suppliers",
            "receipts",
            "inventory",
            "movements",
            "sales",
            "cash",
            "audit",
            "users")) {
      mvc.perform(get("/api/v1/" + resource).with(user("admin").roles("ADMIN")))
          .andExpect(status().isOk());
    }
  }

  private void autoAt(String time) {
    var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    tx.executeWithoutResult(
        s ->
            new CashAutomation(db, true, new BigDecimal("0.00"))
                .synchronizeBranch(1, java.time.ZonedDateTime.parse(time).toInstant()));
  }

  @Test
  void automaticCashOpensAtSixWithoutAnAuthenticatedUser() {
    autoAt("2026-11-01T05:59:59-06:00");
    assertThat(db.queryForObject("SELECT COUNT(*) FROM cash_sessions", Integer.class)).isZero();
    autoAt("2026-11-01T06:00:00-06:00");
    autoAt("2026-11-01T06:00:01-06:00");
    assertThat(db.queryForObject("SELECT COUNT(*) FROM cash_sessions", Integer.class)).isEqualTo(1);
    var row = db.queryForMap("SELECT * FROM cash_sessions");
    assertThat(row.get("actor")).isEqualTo("SYSTEM_CASH");
    assertThat(((java.sql.Timestamp) row.get("opened_at")).toInstant())
        .isEqualTo(java.time.Instant.parse("2026-11-01T12:00:00Z"));
  }

  @Test
  void midnightClosesAutomaticallyAndDoesNotInventCountedCash() {
    autoAt("2026-11-01T06:00:00-06:00");
    for (String method : List.of("CASH", "CARD", "TRANSFER")) {
      db.update(
          "INSERT INTO"
              + " sales(branch_id,cash_session_id,actor,idempotency_key,request_hash,total,method,tendered,change_amount)"
              + " VALUES (1,1,'admin',?,'test',25,?,25,0)",
          UUID.randomUUID().toString(),
          method);
    }
    autoAt("2026-11-01T23:59:59-06:00");
    assertThat(db.queryForMap("SELECT * FROM cash_sessions").get("closed_at")).isNull();
    autoAt("2026-11-02T00:00:00-06:00");
    autoAt("2026-11-02T00:00:01-06:00");
    var closed = db.queryForMap("SELECT * FROM cash_sessions");
    assertThat((BigDecimal) closed.get("expected")).isEqualByComparingTo("25");
    assertThat(closed.get("counted")).isNull();
    assertThat(closed.get("difference")).isNull();
    assertThat(((java.sql.Timestamp) closed.get("closed_at")).toInstant())
        .isEqualTo(java.time.Instant.parse("2026-11-02T06:00:00Z"));
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action='CASH_AUTO_CLOSED'", Integer.class))
        .isEqualTo(1);
    autoAt("2026-11-02T05:59:59-06:00");
    assertThat(db.queryForObject("SELECT COUNT(*) FROM cash_sessions", Integer.class)).isEqualTo(1);
    autoAt("2026-11-02T06:00:00-06:00");
    assertThat(db.queryForObject("SELECT COUNT(*) FROM cash_sessions", Integer.class)).isEqualTo(2);
  }

  @Test
  void automaticCashRecoversAfterDowntimeWithoutInventingMissingDays() {
    autoAt("2026-11-01T08:00:00-06:00");
    autoAt("2026-11-04T10:00:00-06:00");
    assertThat(db.queryForObject("SELECT COUNT(*) FROM cash_sessions", Integer.class)).isEqualTo(2);
    assertThat(
            ((java.sql.Timestamp)
                    db.queryForMap("SELECT * FROM cash_sessions WHERE id=1").get("closed_at"))
                .toInstant())
        .isEqualTo(java.time.Instant.parse("2026-11-02T06:00:00Z"));
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM cash_sessions WHERE closed_at IS NULL", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void automaticCashCannotBeDuplicatedByConcurrentWorkers() throws Exception {
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> autoAt("2026-11-01T06:00:00-06:00"));
      var b = pool.submit(() -> autoAt("2026-11-01T06:00:00-06:00"));
      a.get();
      b.get();
    }
    assertThat(db.queryForObject("SELECT COUNT(*) FROM cash_sessions", Integer.class)).isEqualTo(1);
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action='CASH_AUTO_OPENED'", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void automaticCheckoutWorksWithoutManualOpeningAndRejectsAfterMidnight() {
    receive(3, "AUTO", LocalDate.now().plusYears(2));
    var automation = new CashAutomation(db, true, new BigDecimal("0.00"));
    var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    var daytime =
        new Operations(
            db,
            automation,
            java.time.Clock.fixed(
                java.time.Instant.parse("2026-11-01T18:00:00Z"), java.time.ZoneOffset.UTC));
    tx.executeWithoutResult(
        s ->
            daytime.checkout(
                checkout(tablet, 1, "2.50", "2.50"), UUID.randomUUID().toString(), "admin"));
    assertThat(db.queryForObject("SELECT COUNT(*) FROM sales", Integer.class)).isEqualTo(1);
    var nighttime =
        new Operations(
            db,
            automation,
            java.time.Clock.fixed(
                java.time.Instant.parse("2026-11-02T06:00:00Z"), java.time.ZoneOffset.UTC));
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    s ->
                        nighttime.checkout(
                            checkout(tablet, 1, "2.50", "2.50"),
                            UUID.randomUUID().toString(),
                            "admin")))
        .hasMessageContaining("cerrada por horario");
    assertThat(db.queryForObject("SELECT COUNT(*) FROM sales", Integer.class)).isEqualTo(1);
  }

  @AfterAll
  static void stop() throws Exception {
    pg.close();
  }
}
