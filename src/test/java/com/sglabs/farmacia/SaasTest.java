package com.sglabs.farmacia;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(
    properties = {
      "app.bootstrap-password=Test-only-password-8901",
      "server.servlet.session.cookie.secure=false",
      "app.cash.automatic=false",
      "app.outbox.backoff-seconds=0"
    })
@AutoConfigureMockMvc
class SaasTest {
  static final EmbeddedPostgres pg;
  static final HttpServer receiver;
  static final String SECRET = "whsec_prueba";
  static final List<Received> received = new CopyOnWriteArrayList<>();
  static final AtomicInteger failuresLeft = new AtomicInteger();

  record Received(Map<String, String> headers, String body) {}

  static {
    try {
      pg = EmbeddedPostgres.builder().setPort(0).start();
      receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      receiver.createContext(
          "/hook",
          ex -> {
            var headers = new HashMap<String, String>();
            ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.getFirst()));
            received.add(
                new Received(headers, new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            int status = failuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0 ? 500 : 200;
            ex.sendResponseHeaders(status, -1);
            ex.close();
          });
      receiver.start();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @AfterAll
  static void stop() throws Exception {
    receiver.stop(0);
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
  @Autowired Settings settings;
  @Autowired OutboxPublisher publisher;
  @Autowired EventJobs jobs;
  final ObjectMapper json = new ObjectMapper();
  long presentation;

  @BeforeEach
  void reset() throws Exception {
    received.clear();
    failuresLeft.set(0);
    db.update("DELETE FROM expiry_alerts");
    db.execute(
        "TRUNCATE outbox_events,audit_logs,stock_movements,allocations,sale_lines,sales,cash_sessions,lots,receipts,presentations,products,suppliers RESTART IDENTITY CASCADE");
    db.update("DELETE FROM api_keys");
    db.update("DELETE FROM user_permissions");
    db.update("DELETE FROM users WHERE username<>'admin'");
    db.update(
        "UPDATE settings SET business_name='SG Farma',business_type='FARMACIA',webhook_url=?,webhook_secret=? WHERE id=1",
        "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook",
        SECRET);
    settings.replaceModules(List.of("CASH", "AUDIT", "RX"));
    db.update("INSERT INTO suppliers(name) VALUES ('Proveedor')");
    op.product(
        new Operations.Product(
            "S-1", "Producto", "Genérico", "u", "x", true, false, false, 10,
            List.of(new Operations.Presentation("Unidad", 1, new BigDecimal("5.00"), "B-S1", false))),
        "admin");
    presentation = 1;
  }

  // ---- utilidades -------------------------------------------------------------------------

  void receive(long units, LocalDate expires) {
    op.receive(
        new Operations.Receipt(
            1, "F-" + UUID.randomUUID(),
            List.of(new Operations.ReceiptLine(presentation, units, "L" + units, expires, new BigDecimal("1.00")))),
        "admin");
  }

  Map<String, Object> sell(long quantity) {
    var cart =
        new Operations.Checkout(
            List.of(new Operations.CartLine(presentation, quantity, new BigDecimal("5.00"))),
            "CASH", new BigDecimal(5 * quantity), true);
    return op.checkout(cart, UUID.randomUUID().toString(), "admin");
  }

  JsonNode body(ResultActions r) throws Exception {
    return json.readTree(r.andReturn().getResponse().getContentAsString());
  }

  List<JsonNode> pendingEvents(String type) throws Exception {
    var out = new ArrayList<JsonNode>();
    for (String payload :
        db.queryForList("SELECT payload::text FROM outbox_events WHERE event_type=? ORDER BY id", String.class, type))
      out.add(json.readTree(payload));
    return out;
  }

  void putSettings(Map<String, Object> change) throws Exception {
    var current = body(mvc.perform(get("/api/v1/settings").with(user("admin"))).andExpect(status().isOk()));
    var payload = new LinkedHashMap<String, Object>();
    payload.put("name", current.get("name").asText());
    payload.put("type", current.get("type").asText());
    payload.put("color", current.get("color").asText());
    var modules = new ArrayList<String>();
    current.get("modules").forEach(m -> modules.add(m.asText()));
    payload.put("modules", modules);
    payload.put("webhookUrl", current.get("webhookUrl").asText());
    payload.putAll(change);
    mvc.perform(
            put("/api/v1/settings")
                .with(user("admin"))
                .with(csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(payload)))
        .andExpect(status().isOk());
  }

  String addCashier(String name, String role) {
    db.update(
        "INSERT INTO users(username,password_hash,role,branch_id) VALUES(?,'x',?,1) ON CONFLICT DO NOTHING",
        name, role);
    long id = db.queryForObject("SELECT id FROM users WHERE username=?", Long.class, name);
    for (String k : Permissions.preset(role))
      db.update("INSERT INTO user_permissions(user_id,permission) VALUES(?,?) ON CONFLICT DO NOTHING", id, k);
    return name;
  }

  // ---- marca y módulos --------------------------------------------------------------------

  @Test
  void brandIsPublicAndOnlyAdministratorsChangeSettings() throws Exception {
    var brand = body(mvc.perform(get("/api/v1/settings/public")).andExpect(status().isOk()));
    assertThat(brand.get("name").asText()).isEqualTo("SG Farma");
    assertThat(brand.has("webhookUrl")).isFalse();
    addCashier("cajero", "CASHIER");
    mvc.perform(get("/api/v1/settings").with(user("cajero"))).andExpect(status().isForbidden());
    putSettings(Map.of("name", "Tienda Luna", "type", "TIENDA", "color", "#336699"));
    brand = body(mvc.perform(get("/api/v1/settings/public")).andExpect(status().isOk()));
    assertThat(brand.get("name").asText()).isEqualTo("Tienda Luna");
    assertThat(brand.get("noun").asText()).isEqualTo("tienda");
    assertThat(brand.get("color").asText()).isEqualTo("#336699");
  }

  @Test
  void settingsRejectBadInput() throws Exception {
    for (var bad :
        List.of(
            Map.<String, Object>of("color", "azul"),
            Map.<String, Object>of("modules", List.of("NAVE_ESPACIAL")),
            Map.<String, Object>of("webhookUrl", "ftp://x"),
            Map.<String, Object>of("type", "BANCO"))) {
      var current = new LinkedHashMap<String, Object>(Map.of("name", "X1", "type", "TIENDA", "color", "#112233", "modules", List.of(), "webhookUrl", ""));
      current.putAll(bad);
      mvc.perform(
              put("/api/v1/settings")
                  .with(user("admin"))
                  .with(csrf())
                  .contentType("application/json")
                  .content(json.writeValueAsString(current)))
          .andExpect(status().isBadRequest());
    }
  }

  @Test
  void turningAModuleOffRemovesItsPermissionsEvenForTheAdministrator() throws Exception {
    mvc.perform(get("/api/v1/audit").with(user("admin"))).andExpect(status().isOk());
    putSettings(Map.of("modules", List.of("CASH", "RX")));
    mvc.perform(get("/api/v1/audit").with(user("admin"))).andExpect(status().isForbidden());
    putSettings(Map.of("modules", List.of("AUDIT", "RX")));
    // Abrir y cerrar caja son del módulo; leer la caja la sigue necesitando el punto de venta.
    for (var path : List.of("/api/v1/cash/open", "/api/v1/cash/close"))
      mvc.perform(
              post(path).with(user("admin")).with(csrf()).contentType("application/json").content("{\"amount\":0}"))
          .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/cash").with(user("admin"))).andExpect(status().isOk());
    var me = body(mvc.perform(get("/api/v1/auth/me").with(user("admin"))).andExpect(status().isOk()));
    assertThat(me.get("permissions").toString()).doesNotContain("CASH_");
    assertThat(me.get("modules").toString()).contains("AUDIT").doesNotContain("CASH");
    var catalog = body(mvc.perform(get("/api/v1/users/permissions").with(user("admin"))).andExpect(status().isOk()));
    assertThat(catalog.toString()).doesNotContain("CASH_VIEW");
    putSettings(Map.of("modules", List.of("CASH", "AUDIT", "RX")));
    mvc.perform(
            post("/api/v1/cash/open").with(user("admin")).with(csrf()).contentType("application/json").content("{\"amount\":0}"))
        .andExpect(status().isOk());
  }

  @Test
  void prescriptionIsOnlyRequiredWhileThePharmacyModuleIsOn() {
    receive(20, LocalDate.now().plusYears(1));
    op.open(BigDecimal.ZERO, "admin");
    var noRx =
        new Operations.Checkout(
            List.of(new Operations.CartLine(presentation, 1, new BigDecimal("5.00"))),
            "CASH", new BigDecimal("5.00"), false);
    assertThatThrownBy(() -> op.checkout(noRx, UUID.randomUUID().toString(), "admin"))
        .hasMessageContaining("receta");
    settings.replaceModules(List.of("CASH", "AUDIT"));
    assertThat(op.checkout(noRx, UUID.randomUUID().toString(), "admin")).containsKey("id");
  }

  // ---- eventos hacia n8n ------------------------------------------------------------------

  @Test
  void saleIsDeliveredToN8nWithAValidSignature() throws Exception {
    receive(20, LocalDate.now().plusYears(1));
    op.open(BigDecimal.ZERO, "admin");
    var sale = sell(2);
    assertThat(publisher.publishPending()).isEqualTo(2); // receipt.created + sale.completed
    var delivery =
        received.stream()
            .filter(r -> r.headers().get("x-sg-event").equals("sale.completed"))
            .findFirst()
            .orElseThrow();
    var signature = delivery.headers().get("x-sg-signature");
    long ts = Long.parseLong(signature.split(",")[0].substring(2));
    assertThat(signature.split(",")[1]).isEqualTo("v1=" + OutboxPublisher.sign(SECRET, ts, delivery.body()));
    assertThat(OutboxPublisher.sign("otro-secreto", ts, delivery.body()))
        .isNotEqualTo(signature.split(",")[1].substring(3));
    var envelope = json.readTree(delivery.body());
    assertThat(envelope.get("business").asText()).isEqualTo("SG Farma");
    assertThat(envelope.get("data").get("saleId").asLong()).isEqualTo(((Number) sale.get("id")).longValue());
    assertThat(envelope.get("data").get("total").decimalValue()).isEqualByComparingTo("10.00");
    assertThat(envelope.get("data").get("lines").get(0).get("quantity").asInt()).isEqualTo(2);
    assertThat(db.queryForList("SELECT status FROM outbox_events", String.class)).containsOnly("SENT");
  }

  @Test
  void noEventsAreQueuedWithoutAWebhook() {
    db.update("UPDATE settings SET webhook_url='' WHERE id=1");
    receive(20, LocalDate.now().plusYears(1));
    op.open(BigDecimal.ZERO, "admin");
    sell(1);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isZero();
    assertThat(publisher.publishPending()).isZero();
  }

  @Test
  void failedDeliveryIsRetriedAndThenSent() {
    receive(20, LocalDate.now().plusYears(1));
    failuresLeft.set(1);
    assertThat(publisher.publishPending()).isZero();
    assertThat(db.queryForObject("SELECT status FROM outbox_events", String.class)).isEqualTo("PENDING");
    assertThat(db.queryForObject("SELECT last_error FROM outbox_events", String.class)).contains("500");
    assertThat(publisher.publishPending()).isEqualTo(1);
    assertThat(db.queryForObject("SELECT status FROM outbox_events", String.class)).isEqualTo("SENT");
    assertThat(db.queryForObject("SELECT attempts FROM outbox_events", Integer.class)).isEqualTo(2);
    assertThat(db.queryForObject("SELECT last_error FROM outbox_events", String.class)).isEmpty();
  }

  @Test
  void givesUpAfterTheMaximumNumberOfAttempts() {
    receive(20, LocalDate.now().plusYears(1));
    failuresLeft.set(1000);
    for (int i = 0; i < OutboxPublisher.MAX_ATTEMPTS + 2; i++) publisher.publishPending();
    assertThat(db.queryForObject("SELECT status FROM outbox_events", String.class)).isEqualTo("FAILED");
    assertThat(db.queryForObject("SELECT attempts FROM outbox_events", Integer.class))
        .isEqualTo(OutboxPublisher.MAX_ATTEMPTS);
    int seen = received.size();
    publisher.publishPending();
    assertThat(received).hasSize(seen); // un evento fallido ya no se reenvía
  }

  @Test
  void lowStockIsAnnouncedOnceWhenTheMinimumIsCrossed() throws Exception {
    receive(12, LocalDate.now().plusYears(1)); // mínimo 10
    op.open(BigDecimal.ZERO, "admin");
    sell(1); // 11: aún arriba
    assertThat(pendingEvents("stock.low")).isEmpty();
    sell(2); // 9: cruza
    var low = pendingEvents("stock.low");
    assertThat(low).hasSize(1);
    assertThat(low.getFirst().get("stock").asInt()).isEqualTo(9);
    assertThat(low.getFirst().get("minimum").asInt()).isEqualTo(10);
    sell(1); // 8: ya estaba bajo, no se repite
    assertThat(pendingEvents("stock.low")).hasSize(1);
  }

  @Test
  void expiringLotsAreAnnouncedOncePerThreshold() throws Exception {
    receive(5, LocalDate.now().plusDays(20));
    receive(6, LocalDate.now().plusDays(60));
    receive(7, LocalDate.now().plusDays(200));
    db.update("DELETE FROM outbox_events"); // solo interesan los vencimientos
    assertThat(jobs.checkExpiring()).isEqualTo(2);
    var events = pendingEvents("lot.expiring");
    assertThat(events).hasSize(2);
    for (var e : events) {
      int threshold = e.get("thresholdDays").asInt();
      assertThat(e.get("lots")).hasSize(1);
      assertThat(e.get("lots").get(0).get("units").asInt()).isEqualTo(threshold == 30 ? 5 : 6);
    }
    assertThat(jobs.checkExpiring()).isZero(); // ya avisados
    assertThat(pendingEvents("lot.expiring")).hasSize(2);
  }

  @Test
  void accessChangesAreAnnounced() throws Exception {
    mvc.perform(
            post("/api/v1/users")
                .with(user("admin"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"username\":\"nuevo-usuario\",\"password\":\"Clave-de-prueba-2026\",\"role\":\"CASHIER\"}"))
        .andExpect(status().isOk());
    var events = pendingEvents("user.access_changed");
    assertThat(events).hasSize(1);
    assertThat(events.getFirst().get("username").asText()).isEqualTo("nuevo-usuario");
    assertThat(events.getFirst().get("change").asText()).isEqualTo("CREATED");
  }

  @Test
  void testButtonSendsAPingAndSecretIsNeverReturned() throws Exception {
    var result = body(mvc.perform(post("/api/v1/settings/webhook/test").with(user("admin")).with(csrf())).andExpect(status().isOk()));
    assertThat(result.get("ok").asBoolean()).isTrue();
    assertThat(received.getLast().headers().get("x-sg-event")).isEqualTo("test.ping");
    var current = mvc.perform(get("/api/v1/settings").with(user("admin"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    assertThat(current).doesNotContain(SECRET).contains("\"webhookSecretSet\":true");
    var rotated = body(mvc.perform(post("/api/v1/settings/webhook/secret").with(user("admin")).with(csrf())).andExpect(status().isOk()));
    assertThat(rotated.get("secret").asText()).startsWith("whsec_").hasSizeGreaterThan(30);
    assertThat(settings.get().webhookSecret()).isEqualTo(rotated.get("secret").asText());
    failuresLeft.set(1);
    assertThat(body(mvc.perform(post("/api/v1/settings/webhook/test").with(user("admin")).with(csrf())))
            .get("ok").asBoolean()).isFalse();
  }

  // ---- llaves de API para n8n -------------------------------------------------------------

  String newKey(String label, List<String> permissions) throws Exception {
    return body(
            mvc.perform(
                    post("/api/v1/integrations/keys")
                        .with(user("admin"))
                        .with(csrf())
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("label", label, "permissions", permissions))))
                .andExpect(status().isOk()))
        .get("key")
        .asText();
  }

  @Test
  void aKeyOnlyDoesWhatItWasGiven() throws Exception {
    String key = newKey("n8n lectura", List.of("SALES_VIEW"));
    assertThat(key).startsWith("sgk_");
    var bearer = "Bearer " + key;
    mvc.perform(get("/api/v1/sales").header("Authorization", bearer)).andExpect(status().isOk());
    mvc.perform(get("/api/v1/inventory").header("Authorization", bearer)).andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/users").header("Authorization", bearer)).andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/settings").header("Authorization", bearer)).andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/integrations/keys").header("Authorization", bearer)).andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/products").header("Authorization", bearer).contentType("application/json")
                .content("{\"sku\":\"K1\",\"name\":\"K\",\"baseUnit\":\"u\",\"minimumStock\":0,\"presentations\":[{\"name\":\"X\",\"factor\":1,\"price\":1}]}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void aKeyWithWritePermissionWorksWithoutCsrfOrSession() throws Exception {
    String key = newKey("n8n catálogo", List.of("CATALOG_MANAGE"));
    var result =
        mvc.perform(
                post("/api/v1/products")
                    .header("Authorization", "Bearer " + key)
                    .contentType("application/json")
                    .content("{\"sku\":\"K2\",\"name\":\"Desde n8n\",\"baseUnit\":\"u\",\"minimumStock\":0,\"presentations\":[{\"name\":\"X\",\"factor\":1,\"price\":1}]}"))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(result.getRequest().getSession(false)).isNull();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM products WHERE sku='K2'", Integer.class)).isEqualTo(1);
  }

  @Test
  void invalidOrRevokedKeysAreRejected() throws Exception {
    mvc.perform(get("/api/v1/sales").header("Authorization", "Bearer sgk_inventada")).andExpect(status().isUnauthorized());
    String key = newKey("temporal", List.of("SALES_VIEW"));
    mvc.perform(get("/api/v1/sales").header("Authorization", "Bearer " + key)).andExpect(status().isOk());
    var list = body(mvc.perform(get("/api/v1/integrations/keys").with(user("admin"))).andExpect(status().isOk()));
    assertThat(list.toString()).doesNotContain(key).contains("\"prefix\":\"" + key.substring(0, 12));
    assertThat(list.get(0).get("last_used_at")).isNotNull();
    long id = list.get(0).get("id").asLong();
    mvc.perform(delete("/api/v1/integrations/keys/" + id).with(user("admin")).with(csrf())).andExpect(status().isOk());
    mvc.perform(get("/api/v1/sales").header("Authorization", "Bearer " + key)).andExpect(status().isUnauthorized());
    assertThat(db.queryForList("SELECT action FROM audit_logs ORDER BY id", String.class))
        .contains("INTEGRATION_KEY_CREATED", "INTEGRATION_KEY_REVOKED");
  }

  @Test
  void serviceAccountsDoNotAppearAsPeopleAndCannotLogIn() throws Exception {
    newKey("sin sesión", List.of("SALES_VIEW"));
    var users = body(mvc.perform(get("/api/v1/users").with(user("admin"))).andExpect(status().isOk()));
    for (var u : users) assertThat(u.get("username").asText()).doesNotStartWith("svc-");
    var name = db.queryForObject("SELECT username FROM users WHERE role='SERVICE'", String.class);
    mvc.perform(post("/api/v1/auth/login").with(csrf()).param("username", name).param("password", "cualquiera"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/users")
                .with(user("admin"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"username\":\"falso-svc\",\"password\":\"Clave-de-prueba-2026\",\"role\":\"SERVICE\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void aKeyNeedsAtLeastOnePermission() throws Exception {
    mvc.perform(
            post("/api/v1/integrations/keys")
                .with(user("admin"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"label\":\"vacía\",\"permissions\":[]}"))
        .andExpect(status().isBadRequest());
  }
}
