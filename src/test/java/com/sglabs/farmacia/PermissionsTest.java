package com.sglabs.farmacia;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(
    properties = {
      "app.bootstrap-password=Test-only-password-8901",
      "server.servlet.session.cookie.secure=false",
      "app.cash.automatic=false"
    })
@AutoConfigureMockMvc
class PermissionsTest {
  static final EmbeddedPostgres pg;
  static final String PASSWORD = "Clave-de-prueba-2026";

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

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;
  @Autowired Operations op;
  final ObjectMapper json = new ObjectMapper();

  @BeforeEach
  void clean() {
    db.update("DELETE FROM user_permissions");
    db.update("DELETE FROM users WHERE username<>'admin'");
    db.update("UPDATE users SET active=TRUE WHERE username='admin'");
  }

  // ---- utilidades -------------------------------------------------------------------------

  JsonNode body(org.springframework.test.web.servlet.ResultActions r) throws Exception {
    return json.readTree(r.andReturn().getResponse().getContentAsString());
  }

  /** Crea un usuario desde la API de administración, como lo haría la pantalla. */
  long create(String name, String role, List<String> permissions) throws Exception {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("username", name);
    payload.put("password", PASSWORD);
    payload.put("role", role);
    if (permissions != null) payload.put("permissions", permissions);
    return body(
            mvc.perform(
                    post("/api/v1/users")
                        .with(user("admin"))
                        .with(csrf())
                        .contentType("application/json")
                        .content(json.writeValueAsString(payload)))
                .andExpect(status().isOk()))
        .get("id")
        .asLong();
  }

  void update(long id, Map<String, Object> change, int expected) throws Exception {
    mvc.perform(
            put("/api/v1/users/" + id)
                .with(user("admin"))
                .with(csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(change)))
        .andExpect(status().is(expected));
  }

  int codeFor(String who, MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(request.with(user(who)).with(csrf()))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  String newProduct(String sku) {
    return "{\"sku\":\""
        + sku
        + "\",\"name\":\"P\",\"baseUnit\":\"u\",\"minimumStock\":0,\"presentations\":[{\"name\":\"X\",\"factor\":1,\"price\":1}]}";
  }

  int createProduct(String who, String sku) throws Exception {
    return codeFor(
        who, post("/api/v1/products").contentType("application/json").content(newProduct(sku)));
  }

  MockHttpSession login(String username, String password) throws Exception {
    var r =
        mvc.perform(
                post("/api/v1/auth/login")
                    .with(csrf())
                    .param("username", username)
                    .param("password", password))
            .andExpect(status().isOk())
            .andReturn();
    return (MockHttpSession) r.getRequest().getSession(false);
  }

  // ---- catálogo y plantillas --------------------------------------------------------------

  @Test
  void catalogListsEveryPermissionAndRolePresets() throws Exception {
    var catalog = body(mvc.perform(get("/api/v1/users/permissions").with(user("admin"))).andExpect(status().isOk()));
    assertThat(catalog.get("permissions")).hasSize(Permissions.ALL.size());
    assertThat(catalog.get("roles").get("CASHIER").toString()).contains("POS_SELL").doesNotContain("CATALOG_MANAGE");
    assertThat(catalog.get("roles").get("SUPERVISOR")).hasSize(Permissions.KEYS.size());
  }

  @Test
  void newUserGetsRoleTemplateUnlessAdministratorCustomizes() throws Exception {
    create("cajera", "CASHIER", null);
    create("solo-ver", "CASHIER", List.of("CATALOG_VIEW"));
    var users = body(mvc.perform(get("/api/v1/users").with(user("admin"))).andExpect(status().isOk()));
    for (var u : users) {
      if (u.get("username").asText().equals("cajera"))
        assertThat(u.get("permissions")).hasSize(Permissions.preset("CASHIER").size());
      if (u.get("username").asText().equals("solo-ver"))
        assertThat(u.get("permissions").toString()).isEqualTo("[\"CATALOG_VIEW\"]");
      if (u.get("username").asText().equals("admin"))
        assertThat(u.get("permissions")).hasSize(Permissions.KEYS.size());
    }
  }

  @Test
  void unknownPermissionIsRejected() throws Exception {
    long id = create("ana", "CASHIER", null);
    update(id, Map.of("permissions", List.of("HACKEAR_TODO")), 400);
  }

  // ---- efecto inmediato -------------------------------------------------------------------

  @Test
  void grantingAndRevokingTakeEffectWithoutNewLogin() throws Exception {
    long id = create("ana", "CASHIER", null);
    assertThat(createProduct("ana", "A-1")).isEqualTo(403);
    var withCatalog = new ArrayList<>(Permissions.preset("CASHIER"));
    withCatalog.add("CATALOG_MANAGE");
    update(id, Map.of("permissions", withCatalog), 200);
    assertThat(createProduct("ana", "A-2")).isEqualTo(200);
    update(id, Map.of("permissions", Permissions.preset("CASHIER")), 200);
    assertThat(createProduct("ana", "A-3")).isEqualTo(403);
  }

  @Test
  void deactivatedUserLosesTheOpenSessionAtOnce() throws Exception {
    long id = create("beto", "CASHIER", null);
    var session = login("beto", PASSWORD);
    mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isOk());
    update(id, Map.of("active", false), 200);
    mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isUnauthorized());
    assertThat(session.isInvalid()).isTrue();
    mvc.perform(post("/api/v1/auth/login").with(csrf()).param("username", "beto").param("password", PASSWORD))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void meReturnsEffectivePermissions() throws Exception {
    create("caro", "INVENTORY", List.of("INVENTORY_VIEW"));
    var me = body(mvc.perform(get("/api/v1/auth/me").with(user("caro"))).andExpect(status().isOk()));
    assertThat(me.get("permissions").toString()).isEqualTo("[\"INVENTORY_VIEW\"]");
    var admin = body(mvc.perform(get("/api/v1/auth/me").with(user("admin"))).andExpect(status().isOk()));
    assertThat(admin.get("permissions")).hasSize(Permissions.KEYS.size());
  }

  // ---- salvaguardas -----------------------------------------------------------------------

  @Test
  void onlyAdministratorsManageUsers() throws Exception {
    create("sup", "SUPERVISOR", null); // todos los permisos, pero no es ADMIN
    assertThat(codeFor("sup", get("/api/v1/users"))).isEqualTo(403);
    assertThat(codeFor("sup", get("/api/v1/users/permissions"))).isEqualTo(403);
    assertThat(
            codeFor("sup",
                post("/api/v1/users")
                    .contentType("application/json")
                    .content("{\"username\":\"x-user\",\"password\":\"" + PASSWORD + "\",\"role\":\"ADMIN\"}")))
        .isEqualTo(403);
  }

  @Test
  void administratorCannotLockThemselvesOut() throws Exception {
    long self = db.queryForObject("SELECT id FROM users WHERE username='admin'", Long.class);
    update(self, Map.of("active", false), 409);
    update(self, Map.of("role", "CASHIER"), 409);
    assertThat(db.queryForObject("SELECT active FROM users WHERE username='admin'", Boolean.class)).isTrue();
  }

  @Test
  void usersFromAnotherBranchCannotBeEdited() throws Exception {
    db.update("INSERT INTO branches(id,name) VALUES(2,'Otra') ON CONFLICT DO NOTHING");
    db.update("INSERT INTO users(username,password_hash,role,branch_id) VALUES('ajeno','x','CASHIER',2)");
    long id = db.queryForObject("SELECT id FROM users WHERE username='ajeno'", Long.class);
    update(id, Map.of("active", false), 404);
    mvc.perform(
            post("/api/v1/users/" + id + "/password")
                .with(user("admin"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void anyUserCanChangeOwnPasswordAndAdministratorCanReset() throws Exception {
    long id = create("dora", "ACCOUNTANT", null);
    assertThat(
            codeFor("dora",
                post("/api/v1/users/password")
                    .contentType("application/json")
                    .content("{\"password\":\"Otra-clave-segura-1\"}")))
        .isEqualTo(200);
    mvc.perform(
            post("/api/v1/users/" + id + "/password")
                .with(user("admin"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"password\":\"Clave-restablecida-2\"}"))
        .andExpect(status().isOk());
    login("dora", "Clave-restablecida-2");
  }

  // ---- qué ve cada persona ----------------------------------------------------------------

  @Test
  void purchaseCostsAreHiddenWithoutCostsView() throws Exception {
    op.product(
        new Operations.Product(
            "C-1", "Costo", "", "u", "x", false, false, false, 1,
            List.of(new Operations.Presentation("Caja", 1, new BigDecimal("5.00"), "B-COST", false))),
        "admin");
    db.update("INSERT INTO suppliers(name) VALUES('P')");
    long presentation = db.queryForObject("SELECT MAX(id) FROM presentations", Long.class);
    op.receive(
        new Operations.Receipt(
            db.queryForObject("SELECT MAX(id) FROM suppliers", Long.class),
            "F-" + UUID.randomUUID(),
            List.of(new Operations.ReceiptLine(presentation, 2, "L1", LocalDate.now().plusYears(1), new BigDecimal("8.00")))),
        "admin");
    create("ve-costos", "INVENTORY", List.of("INVENTORY_VIEW", "COSTS_VIEW"));
    create("sin-costos", "INVENTORY", List.of("INVENTORY_VIEW"));
    var withCosts = body(mvc.perform(get("/api/v1/inventory").with(user("ve-costos"))).andExpect(status().isOk()));
    var without = body(mvc.perform(get("/api/v1/inventory").with(user("sin-costos"))).andExpect(status().isOk()));
    assertThat(withCosts.get(0).has("unit_cost")).isTrue();
    assertThat(without.get(0).has("unit_cost")).isFalse();
  }

  @Test
  void cashierWithoutSalesViewSeesOnlyOwnReceipts() throws Exception {
    op.product(
        new Operations.Product(
            "V-1", "Venta", "", "u", "x", false, false, false, 1,
            List.of(new Operations.Presentation("Unidad", 1, new BigDecimal("3.00"), "B-V", false))),
        "admin");
    db.update("INSERT INTO suppliers(name) VALUES('P2')");
    long presentation = db.queryForObject("SELECT MAX(id) FROM presentations", Long.class);
    op.receive(
        new Operations.Receipt(
            db.queryForObject("SELECT MAX(id) FROM suppliers", Long.class),
            "F-" + UUID.randomUUID(),
            List.of(new Operations.ReceiptLine(presentation, 10, "L2", LocalDate.now().plusYears(1), new BigDecimal("10.00")))),
        "admin");
    op.open(BigDecimal.ZERO, "admin");
    long cashierId = create("vendedor", "CASHIER", List.of("POS_SELL", "CASH_VIEW"));
    var cart = new Operations.Checkout(
        List.of(new Operations.CartLine(presentation, 1, new BigDecimal("3.00"))), "CASH", new BigDecimal("3.00"), false);
    var others = op.checkout(cart, UUID.randomUUID().toString(), "admin");
    op.open(BigDecimal.ZERO, "vendedor");
    var mine = op.checkout(cart, UUID.randomUUID().toString(), "vendedor");
    mvc.perform(get("/api/v1/sales/" + mine.get("id")).with(user("vendedor"))).andExpect(status().isOk());
    mvc.perform(get("/api/v1/sales/" + others.get("id")).with(user("vendedor"))).andExpect(status().isNotFound());
    assertThat(codeFor("vendedor", get("/api/v1/sales"))).isEqualTo(403);
    assertThat(cashierId).isPositive();
  }

  // ---- auditoría --------------------------------------------------------------------------

  @Test
  void everyChangeLeavesAuditTrail() throws Exception {
    long id = create("eva", "CASHIER", List.of("POS_SELL"));
    update(id, Map.of("permissions", List.of("CATALOG_VIEW"), "active", false), 200);
    var actions = db.queryForList("SELECT action||' '||reference FROM audit_logs ORDER BY id", String.class);
    assertThat(actions)
        .contains(
            "USER_CREATED eva",
            "PERMISSION_GRANTED eva:POS_SELL",
            "PERMISSION_GRANTED eva:CATALOG_VIEW",
            "PERMISSION_REVOKED eva:POS_SELL",
            "USER_DEACTIVATED eva");
  }
}
