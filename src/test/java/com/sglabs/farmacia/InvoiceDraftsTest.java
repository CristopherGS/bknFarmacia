package com.sglabs.farmacia;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(
    properties = {
      "app.bootstrap-password=Test-only-password-8901",
      "server.servlet.session.cookie.secure=false",
      "app.cash.automatic=false"
    })
@AutoConfigureMockMvc
class InvoiceDraftsTest {
  static final EmbeddedPostgres pg;
  static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1, 2, 3};

  static {
    try {
      pg = EmbeddedPostgres.builder().setPort(0).start();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @AfterAll
  static void closeDatabase() throws Exception {
    pg.close();
  }

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> pg.getJdbcUrl("postgres", "postgres"));
    r.add("spring.datasource.username", () -> "postgres");
    r.add("spring.datasource.password", () -> "");
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;
  @Autowired Operations op;
  @Autowired EventJobs jobs;
  final ObjectMapper json = new ObjectMapper();

  @BeforeEach
  void reset() {
    db.execute(
        "TRUNCATE outbox_events,audit_logs,stock_movements,allocations,sale_lines,sales,cash_sessions,lots,receipts,presentations,products,suppliers,receipt_drafts RESTART IDENTITY CASCADE");
    db.update("DELETE FROM api_keys");
    db.update("DELETE FROM user_permissions");
    db.update("DELETE FROM users WHERE username<>'admin'");
    db.update("UPDATE settings SET webhook_url='http://127.0.0.1:9/hook',webhook_secret='whsec_x' WHERE id=1");
    op.product(
        new Operations.Product(
            "ACE-500", "Acetaminofén 500 mg", "Paracetamol", "tableta", "x", false, false, false, 10,
            List.of(
                new Operations.Presentation("Caja x100", 100, new BigDecimal("45.00"), "7400000000011", false),
                new Operations.Presentation("Blíster x10", 10, new BigDecimal("6.00"), "7400000000028", false))),
        "admin");
    op.product(
        new Operations.Product(
            "AMX-500", "Amoxicilina 500 mg", "Amoxicilina", "capsula", "x", false, false, false, 5,
            List.of(new Operations.Presentation("Caja x21", 21, new BigDecimal("60.00"), "7400000000035", false))),
        "admin");
    db.update("INSERT INTO suppliers(name,nit) VALUES ('Distribuidora Central, S.A.','1234567-8')");
    add("comprador", "INVENTORY", 1);
    add("cajero", "CASHIER", 1);
  }

  String add(String name, String role, long branch) {
    db.update("INSERT INTO branches(id,name) VALUES(2,'Otra') ON CONFLICT DO NOTHING");
    db.update(
        "INSERT INTO users(username,password_hash,role,branch_id) VALUES(?,'x',?,?) ON CONFLICT DO NOTHING", name, role, branch);
    long id = db.queryForObject("SELECT id FROM users WHERE username=?", Long.class, name);
    for (String k : Permissions.preset(role))
      db.update("INSERT INTO user_permissions(user_id,permission) VALUES(?,?) ON CONFLICT DO NOTHING", id, k);
    return name;
  }

  JsonNode body(ResultActions r) throws Exception {
    return json.readTree(r.andReturn().getResponse().getContentAsString());
  }

  long upload(String user, byte[] bytes, String filename) throws Exception {
    return body(
            mvc.perform(
                    multipart("/api/v1/receipts/drafts")
                        .file(new MockMultipartFile("file", filename, "image/jpeg", bytes))
                        .with(user(user))
                        .with(csrf()))
                .andExpect(status().isOk()))
        .get("id")
        .asLong();
  }

  String importKey(List<String> permissions) throws Exception {
    return body(
            mvc.perform(
                    post("/api/v1/integrations/keys")
                        .with(user("admin"))
                        .with(csrf())
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("label", "lector n8n", "permissions", permissions))))
                .andExpect(status().isOk()))
        .get("key")
        .asText();
  }

  static final String EXTRACTION =
      """
      {"supplierName":"DISTRIBUIDORA CENTRAL SA","supplierNit":"12345678","document":"FAC-0099",
       "lines":[
        {"description":"Acetaminofen 500mg caja","barcode":"7400000000011","quantity":3,"lot":"L-77","expiresOn":"2028-05-01","totalCost":135.5},
        {"description":"AMOXICILINA 500MG CAJA X21","quantity":2,"totalCost":80},
        {"description":"Flete y manejo","quantity":1,"expiresOn":"pronto","totalCost":15}
       ]}""";

  ResultActions extract(String key, long id, String payload) throws Exception {
    return mvc.perform(
        put("/api/v1/receipts/drafts/" + id + "/extraction")
            .header("Authorization", "Bearer " + key)
            .contentType("application/json")
            .content(payload));
  }

  // ---- subida -----------------------------------------------------------------------------

  @Test
  void uploadKeepsTheRealFileTypeAndNotifiesN8n() throws Exception {
    long id = upload("comprador", JPEG, "../../etc/factura 1.jpg");
    var row = db.queryForMap("SELECT * FROM receipt_drafts WHERE id=?", id);
    assertThat(row.get("status")).isEqualTo("PENDING");
    assertThat(row.get("content_type")).isEqualTo("image/jpeg");
    assertThat((String) row.get("filename")).doesNotContain("/").doesNotContain("..");
    var event = db.queryForObject("SELECT payload::text FROM outbox_events WHERE event_type='invoice.uploaded'", String.class);
    assertThat(json.readTree(event).get("draftId").asLong()).isEqualTo(id);
    assertThat(json.readTree(event).get("filePath").asText()).endsWith("/" + id + "/file");
    var file = mvc.perform(get("/api/v1/receipts/drafts/" + id + "/file").with(user("comprador"))).andExpect(status().isOk()).andReturn().getResponse();
    assertThat(file.getContentAsByteArray()).isEqualTo(JPEG);
    assertThat(file.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(file.getHeader("Content-Disposition")).startsWith("attachment");
  }

  @Test
  void uploadRejectsWhatIsNotAnInvoiceImage() throws Exception {
    for (var bad : List.of("hola mundo".getBytes(), new byte[0], new byte[InvoiceDraftsController.MAX_BYTES + 1])) {
      mvc.perform(
              multipart("/api/v1/receipts/drafts")
                  .file(new MockMultipartFile("file", "factura.jpg", "image/jpeg", bad))
                  .with(user("comprador")).with(csrf()))
          .andExpect(status().isBadRequest());
    }
    // Una página web con nombre de imagen tampoco pasa: se mira el contenido, no el nombre.
    mvc.perform(
            multipart("/api/v1/receipts/drafts")
                .file(new MockMultipartFile("file", "x.png", "image/png", "<html><script>alert(1)</script>".getBytes()))
                .with(user("comprador")).with(csrf()))
        .andExpect(status().isBadRequest());
    assertThat(db.queryForObject("SELECT COUNT(*) FROM receipt_drafts", Integer.class)).isZero();
  }

  @Test
  void uploadNeedsThePermissionAndAConnectedN8n() throws Exception {
    mvc.perform(
            multipart("/api/v1/receipts/drafts")
                .file(new MockMultipartFile("file", "f.jpg", "image/jpeg", JPEG))
                .with(user("cajero")).with(csrf()))
        .andExpect(status().isForbidden());
    db.update("UPDATE settings SET webhook_url='' WHERE id=1");
    mvc.perform(
            multipart("/api/v1/receipts/drafts")
                .file(new MockMultipartFile("file", "f.jpg", "image/jpeg", JPEG))
                .with(user("comprador")).with(csrf()))
        .andExpect(status().isConflict());
  }

  // ---- lectura ----------------------------------------------------------------------------

  @Test
  void theReaderReturnsLinesAndTheyAreMatchedToTheCatalog() throws Exception {
    long id = upload("comprador", JPEG, "f.jpg");
    String key = importKey(List.of("INVOICES_IMPORT"));
    var result = body(extract(key, id, EXTRACTION).andExpect(status().isOk()));
    assertThat(result.get("status").asText()).isEqualTo("READY");
    assertThat(result.get("matched").asInt()).isEqualTo(2);
    var draft = body(mvc.perform(get("/api/v1/receipts/drafts/" + id).with(user("comprador"))).andExpect(status().isOk()));
    assertThat(draft.get("supplier_id").asLong()).isEqualTo(1);
    assertThat(draft.get("document").asText()).isEqualTo("FAC-0099");
    var lines = draft.get("lines");
    assertThat(lines.get(0).get("presentation_id").asLong()).isEqualTo(1);
    assertThat(lines.get(0).get("method").asText()).isEqualTo("BARCODE");
    assertThat(lines.get(0).get("expires_on").asText()).isEqualTo("2028-05-01");
    assertThat(lines.get(1).get("presentation_id").asLong()).isEqualTo(3);
    assertThat(lines.get(1).get("method").asText()).isEqualTo("NAME");
    assertThat(lines.get(2).get("presentation_id").isNull()).isTrue(); // el flete no es un producto
    assertThat(lines.get(2).get("expires_on").isNull()).isTrue(); // "pronto" no es una fecha
    assertThat(db.queryForList("SELECT event_type FROM outbox_events", String.class)).contains("invoice.ready");
  }

  @Test
  void aReadFailureIsRecordedAndCanBeRetried() throws Exception {
    long id = upload("comprador", JPEG, "f.jpg");
    String key = importKey(List.of("INVOICES_IMPORT"));
    extract(key, id, "{\"error\":\"La foto está borrosa\"}").andExpect(status().isOk());
    var failed = db.queryForMap("SELECT status,error FROM receipt_drafts WHERE id=?", id);
    assertThat(failed.get("status")).isEqualTo("FAILED");
    assertThat(failed.get("error")).isEqualTo("La foto está borrosa");
    extract(key, id, EXTRACTION).andExpect(status().isOk());
    assertThat(db.queryForObject("SELECT status FROM receipt_drafts WHERE id=?", String.class, id)).isEqualTo("READY");
    extract(key, id, "{\"lines\":[]}").andExpect(status().isBadRequest());
  }

  @Test
  void aReaderKeyCanReadInvoicesButCannotBookPurchases() throws Exception {
    long id = upload("comprador", JPEG, "f.jpg");
    String key = importKey(List.of("INVOICES_IMPORT"));
    mvc.perform(get("/api/v1/receipts/drafts/" + id + "/file").header("Authorization", "Bearer " + key)).andExpect(status().isOk());
    extract(key, id, EXTRACTION).andExpect(status().isOk());
    mvc.perform(
            post("/api/v1/receipts/drafts/" + id + "/confirm").header("Authorization", "Bearer " + key)
                .contentType("application/json")
                .content("{\"supplierId\":1,\"document\":\"X\",\"lines\":[{\"presentationId\":1,\"quantity\":1,\"lot\":\"A\",\"expiresOn\":\"2030-01-01\",\"totalCost\":1}]}"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/inventory").header("Authorization", "Bearer " + key)).andExpect(status().isForbidden());
    assertThat(db.queryForObject("SELECT COUNT(*) FROM lots", Integer.class)).isZero();
  }

  // ---- confirmación -----------------------------------------------------------------------

  String confirmBody(long presentation, int quantity) {
    return "{\"supplierId\":1,\"document\":\"FAC-0099\",\"lines\":[{\"presentationId\":" + presentation
        + ",\"quantity\":" + quantity + ",\"lot\":\"L-77\",\"expiresOn\":\"2030-05-01\",\"totalCost\":135.5}]}";
  }

  @Test
  void aPersonConfirmsAfterReviewAndStockEntersOnlyThen() throws Exception {
    long id = upload("comprador", JPEG, "f.jpg");
    extract(importKey(List.of("INVOICES_IMPORT")), id, EXTRACTION).andExpect(status().isOk());
    assertThat(db.queryForObject("SELECT COUNT(*) FROM lots", Integer.class)).isZero(); // leer no ingresa nada
    var confirmed =
        body(mvc.perform(post("/api/v1/receipts/drafts/" + id + "/confirm").with(user("comprador")).with(csrf())
                .contentType("application/json").content(confirmBody(1, 3))).andExpect(status().isOk()));
    var row = db.queryForMap("SELECT status,receipt_id FROM receipt_drafts WHERE id=?", id);
    assertThat(row.get("status")).isEqualTo("CONFIRMED");
    assertThat(((Number) row.get("receipt_id")).longValue()).isEqualTo(confirmed.get("id").asLong());
    assertThat(db.queryForObject("SELECT SUM(qty_base) FROM lots", Long.class)).isEqualTo(300); // 3 cajas x 100
    assertThat(db.queryForList("SELECT action FROM audit_logs", String.class)).contains("INVOICE_UPLOADED", "INVOICE_READ", "INVOICE_CONFIRMED", "RECEIPT_CREATED");
    // Confirmar dos veces no duplica las existencias.
    mvc.perform(post("/api/v1/receipts/drafts/" + id + "/confirm").with(user("comprador")).with(csrf())
            .contentType("application/json").content(confirmBody(1, 3))).andExpect(status().isConflict());
    assertThat(db.queryForObject("SELECT SUM(qty_base) FROM lots", Long.class)).isEqualTo(300);
    mvc.perform(post("/api/v1/receipts/drafts/" + id + "/discard").with(user("comprador")).with(csrf())).andExpect(status().isConflict());
  }

  @Test
  void onlyAReadyDraftCanBeConfirmedAndOnlyByWhoBooksPurchases() throws Exception {
    long id = upload("comprador", JPEG, "f.jpg");
    mvc.perform(post("/api/v1/receipts/drafts/" + id + "/confirm").with(user("comprador")).with(csrf())
            .contentType("application/json").content(confirmBody(1, 1))).andExpect(status().isConflict()); // aún sin leer
    extract(importKey(List.of("INVOICES_IMPORT")), id, EXTRACTION).andExpect(status().isOk());
    mvc.perform(post("/api/v1/receipts/drafts/" + id + "/confirm").with(user("cajero")).with(csrf())
            .contentType("application/json").content(confirmBody(1, 1))).andExpect(status().isForbidden());
  }

  @Test
  void discardingLeavesNoStockAndBlocksLaterReading() throws Exception {
    long id = upload("comprador", JPEG, "f.jpg");
    mvc.perform(post("/api/v1/receipts/drafts/" + id + "/discard").with(user("comprador")).with(csrf())).andExpect(status().isOk());
    assertThat(db.queryForObject("SELECT status FROM receipt_drafts WHERE id=?", String.class, id)).isEqualTo("DISCARDED");
    extract(importKey(List.of("INVOICES_IMPORT")), id, EXTRACTION).andExpect(status().isConflict());
    assertThat(db.queryForObject("SELECT COUNT(*) FROM lots", Integer.class)).isZero();
  }

  @Test
  void draftsOfAnotherBranchAreInvisible() throws Exception {
    long id = upload("comprador", JPEG, "f.jpg");
    add("ajeno", "INVENTORY", 2);
    mvc.perform(get("/api/v1/receipts/drafts/" + id).with(user("ajeno"))).andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/receipts/drafts/" + id + "/file").with(user("ajeno"))).andExpect(status().isNotFound());
    mvc.perform(post("/api/v1/receipts/drafts/" + id + "/discard").with(user("ajeno")).with(csrf())).andExpect(status().isNotFound());
    assertThat(body(mvc.perform(get("/api/v1/receipts/drafts").with(user("ajeno"))).andExpect(status().isOk()))).isEmpty();
  }

  @Test
  void oldClosedInvoiceFilesArePurgedButTheHistoryStays() throws Exception {
    long old = upload("comprador", JPEG, "vieja.jpg");
    long fresh = upload("comprador", JPEG, "nueva.jpg");
    db.update("UPDATE receipt_drafts SET status='DISCARDED' WHERE id IN (?,?)", old, fresh);
    db.update("UPDATE receipt_drafts SET updated_at=CURRENT_TIMESTAMP - interval '40 days' WHERE id=?", old);
    assertThat(jobs.purgeOldInvoiceFiles()).isEqualTo(1);
    mvc.perform(get("/api/v1/receipts/drafts/" + old + "/file").with(user("comprador"))).andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/receipts/drafts/" + fresh + "/file").with(user("comprador"))).andExpect(status().isOk());
    assertThat(db.queryForObject("SELECT COUNT(*) FROM receipt_drafts", Integer.class)).isEqualTo(2);
  }

  @Test
  void importPermissionImpliesViewingPurchases() {
    assertThat(Permissions.normalize(List.of("INVOICES_IMPORT"))).containsExactly("PURCHASES_VIEW", "INVOICES_IMPORT");
  }
}
