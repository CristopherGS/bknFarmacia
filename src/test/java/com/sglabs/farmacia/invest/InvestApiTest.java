package com.sglabs.farmacia.invest;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
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
      "app.invest.owner=admin"
    })
@AutoConfigureMockMvc
class InvestApiTest {
  static final EmbeddedPostgres pg;

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
  @Autowired InvestStore store;
  final ObjectMapper json = new ObjectMapper();

  static String fixture(String name) {
    try {
      return Files.readString(Path.of("src/test/resources/invest/" + name));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @BeforeEach
  void reset() {
    db.execute("TRUNCATE invest_positions,invest_contributions,invest_prices,invest_macro,invest_fx,invest_snapshots,invest_news,invest_state RESTART IDENTITY");
    db.update("DELETE FROM invest_config");
    db.update("DELETE FROM user_permissions WHERE user_id IN (SELECT id FROM users WHERE username<>'admin')");
    db.update("DELETE FROM users WHERE username<>'admin'");
    db.update("INSERT INTO users(username,password_hash,role,branch_id) VALUES('otroadmin','x','ADMIN',1),('cajero','x','CASHIER',1)");
  }

  JsonNode body(ResultActions r) throws Exception {
    return json.readTree(r.andReturn().getResponse().getContentAsString());
  }

  // ---- acceso ----

  @Test
  void onlyTheConfiguredOwnerGetsIn() throws Exception {
    mvc.perform(get("/api/v1/invest/latest").with(user("admin"))).andExpect(status().isOk());
    mvc.perform(get("/api/v1/invest/latest").with(user("otroadmin"))).andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/invest/latest").with(user("cajero"))).andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/invest/config").with(user("otroadmin"))).andExpect(status().isForbidden());
    mvc.perform(post("/api/v1/invest/refresh").with(user("otroadmin")).with(csrf())).andExpect(status().isForbidden());
    mvc.perform(post("/api/v1/invest/positions").with(user("cajero")).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/invest/latest")).andExpect(status().isUnauthorized());
  }

  @Test
  void meAdvertisesTheFeatureOnlyToTheOwner() throws Exception {
    assertThat(body(mvc.perform(get("/api/v1/auth/me").with(user("admin")))).at("/features/invest").asBoolean()).isTrue();
    assertThat(body(mvc.perform(get("/api/v1/auth/me").with(user("otroadmin")))).at("/features/invest").asBoolean()).isFalse();
  }

  // ---- configuración y datos ----

  @Test
  void configStartsWithTheDefaultPlanAndRejectsNonsense() throws Exception {
    var cfg = body(mvc.perform(get("/api/v1/invest/config").with(user("admin"))).andExpect(status().isOk()));
    assertThat(cfg.at("/target/VOO").asDouble()).isEqualTo(50);
    assertThat(cfg.get("goalGtq").asDouble()).isEqualTo(100000);

    var bad = (com.fasterxml.jackson.databind.node.ObjectNode) cfg.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) bad.get("target")).put("VOO", 80);
    mvc.perform(put("/api/v1/invest/config").with(user("admin")).with(csrf()).contentType("application/json").content(bad.toString()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("100%")));

    var ok = (com.fasterxml.jackson.databind.node.ObjectNode) cfg.deepCopy();
    ok.put("monthlyContributionGtq", 3500);
    mvc.perform(put("/api/v1/invest/config").with(user("admin")).with(csrf()).contentType("application/json").content(ok.toString())).andExpect(status().isOk());
    assertThat(store.config().monthlyContributionGtq()).isEqualTo(3500);
  }

  @Test
  void positionsAndContributionsCanBeManagedAndAreValidated() throws Exception {
    long id = body(mvc.perform(post("/api/v1/invest/positions").with(user("admin")).with(csrf()).contentType("application/json")
            .content("{\"ticker\":\"voo\",\"shares\":2.5,\"avgPriceUsd\":480.1,\"boughtOn\":\"2026-01-15\"}")).andExpect(status().isOk())).get("id").asLong();
    assertThat(store.positions()).singleElement().satisfies(p -> {
      assertThat(p.ticker()).isEqualTo("VOO");
      assertThat(p.shares()).isEqualTo(2.5);
    });
    mvc.perform(put("/api/v1/invest/positions/" + id).with(user("admin")).with(csrf()).contentType("application/json")
            .content("{\"ticker\":\"VOO\",\"shares\":3,\"avgPriceUsd\":480.1,\"boughtOn\":\"2026-01-15\"}")).andExpect(status().isOk());
    assertThat(store.positions().getFirst().shares()).isEqualTo(3);

    for (String bad : List.of(
        "{\"ticker\":\"VOO\",\"shares\":-1,\"avgPriceUsd\":1,\"boughtOn\":\"2026-01-15\"}",
        "{\"ticker\":\"\",\"shares\":1,\"avgPriceUsd\":1,\"boughtOn\":\"2026-01-15\"}",
        "{\"ticker\":\"VOO; DROP\",\"shares\":1,\"avgPriceUsd\":1,\"boughtOn\":\"2026-01-15\"}",
        "{\"ticker\":\"VOO\",\"shares\":1,\"avgPriceUsd\":1}"))
      mvc.perform(post("/api/v1/invest/positions").with(user("admin")).with(csrf()).contentType("application/json").content(bad)).andExpect(status().isBadRequest());

    mvc.perform(delete("/api/v1/invest/positions/" + id).with(user("admin")).with(csrf())).andExpect(status().isOk());
    mvc.perform(delete("/api/v1/invest/positions/" + id).with(user("admin")).with(csrf())).andExpect(status().isNotFound());

    long cid = body(mvc.perform(post("/api/v1/invest/contributions").with(user("admin")).with(csrf()).contentType("application/json")
            .content("{\"day\":\"2026-02-01\",\"gtq\":1540,\"fx\":7.7}")).andExpect(status().isOk())).get("id").asLong();
    assertThat(store.contributions().getFirst().usd()).isEqualTo(200.0);
    mvc.perform(delete("/api/v1/invest/contributions/" + cid).with(user("admin")).with(csrf())).andExpect(status().isOk());
  }

  // ---- actualización con fuentes simuladas ----

  /** Responde con los archivos de prueba; lo que no conoce falla, como una fuente caída. */
  static class Stub implements HttpFetcher {
    final List<String> calls = new ArrayList<>();
    boolean down;

    @Override
    public String get(String url, Map<String, String> h) throws IOException {
      calls.add(url);
      if (down) throw new IOException("sin conexión");
      if (url.contains("/v8/finance/chart/%5EGSPC")) return fixture("yahoo-gspc-1mo.json");
      if (url.contains("/v8/finance/chart/VOO")) return fixture("yahoo-voo-1mo.json");
      if (url.contains("id=VIXCLS")) return fixture("fred-vix.csv");
      if (url.contains("id=T10Y2Y")) return fixture("fred-t10y2y.csv");
      throw new IOException("HTTP 404");
    }

    @Override
    public String post(String url, String body, Map<String, String> h) throws IOException {
      calls.add(url);
      if (down) throw new IOException("sin conexión");
      if (url.contains("banguat")) return fixture("banguat-dia.xml");
      throw new IOException("HTTP 404");
    }
  }

  @Test
  void aRefreshBuildsTheDailySummaryAndReportsWhichSourcesFailed() throws Exception {
    var cfg = InvestConfig.defaults();
    cfg = new InvestConfig(Map.of("VOO", 100.0), Map.of("VOO", "equity_us"), cfg.rebalanceBandPts(), cfg.concentrationLimitPct(), cfg.monthlyContributionGtq(), cfg.goalGtq(), cfg.emergencyFundMinGtq(), cfg.emergencyFundCurrentGtq(), cfg.fxFallbackGtqPerUsd(), cfg.expectedAnnualReturnPct(), cfg.dropRules(), cfg.stress());
    store.saveConfig(cfg);
    var voo = PriceSource.Yahoo.parse(fixture("yahoo-voo-1mo.json"));
    store.addPosition("VOO", 2, voo.getFirst().close(), voo.getFirst().day());

    var http = new Stub();
    var service = new InvestService(store, http, "", "", "", "", "", Clock.fixed(voo.getLast().day().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
    assertThat(service.refresh()).isTrue();
    assertThat(service.status().error()).isNull();

    var snap = json.readTree(store.latestSnapshot().orElseThrow());
    assertThat(snap.at("/portfolio/hasPositions").asBoolean()).isTrue();
    assertThat(snap.at("/portfolio/valueUsd").asDouble()).isCloseTo(2 * voo.getLast().close(), within(0.01));
    assertThat(snap.at("/fx/gtqPerUsd").asDouble()).isPositive();
    assertThat(snap.at("/market/indicators").size()).isGreaterThanOrEqualTo(4);
    assertThat(snap.at("/sources/prices/VOO/stale").asBoolean()).isFalse();
    assertThat(snap.at("/sources/macro/DGS10/error").asText()).isNotBlank(); // esa serie no estaba disponible
    assertThat(snap.at("/sources/news/stale").asBoolean()).isTrue();
    assertThat(snap.at("/portfolio/risk/enoughData").asBoolean()).isFalse(); // solo 23 días de historial

    var latest = body(mvc.perform(get("/api/v1/invest/latest").with(user("admin"))).andExpect(status().isOk()));
    assertThat(latest.at("/portfolio/valueUsd").asDouble()).isEqualTo(snap.at("/portfolio/valueUsd").asDouble());
    assertThat(body(mvc.perform(get("/api/v1/invest/history").with(user("admin"))).andExpect(status().isOk())).size()).isEqualTo(1);
  }

  @Test
  void whenEverySourceGoesDownTheLastValidDataIsKeptAndMarkedStale() throws Exception {
    var cfg = InvestConfig.defaults();
    store.saveConfig(new InvestConfig(Map.of("VOO", 100.0), Map.of("VOO", "equity_us"), cfg.rebalanceBandPts(), cfg.concentrationLimitPct(), cfg.monthlyContributionGtq(), cfg.goalGtq(), cfg.emergencyFundMinGtq(), cfg.emergencyFundCurrentGtq(), cfg.fxFallbackGtqPerUsd(), cfg.expectedAnnualReturnPct(), cfg.dropRules(), cfg.stress()));
    var voo = PriceSource.Yahoo.parse(fixture("yahoo-voo-1mo.json"));
    store.addPosition("VOO", 1, voo.getFirst().close(), voo.getFirst().day());

    var http = new Stub();
    var day1 = Clock.fixed(voo.getLast().day().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    new InvestService(store, http, "", "", "", "", "", day1).refresh();
    double before = json.readTree(store.latestSnapshot().orElseThrow()).at("/portfolio/valueUsd").asDouble();
    int pricesBefore = db.queryForObject("SELECT COUNT(*) FROM invest_prices", Integer.class);

    http.down = true;
    var later = Clock.fixed(voo.getLast().day().plusDays(12).atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    new InvestService(store, http, "", "", "", "", "", later).refresh();

    var snap = json.readTree(store.latestSnapshot().orElseThrow());
    assertThat(snap.at("/portfolio/valueUsd").asDouble()).isEqualTo(before);
    assertThat(snap.at("/sources/prices/VOO/stale").asBoolean()).isTrue();
    assertThat(snap.at("/sources/prices/VOO/asOf").asText()).isEqualTo(voo.getLast().day().toString());
    assertThat(snap.at("/sources/prices/VOO/error").asText()).contains("yahoo");
    assertThat(snap.at("/fx/stale").asBoolean()).isTrue();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM invest_prices", Integer.class)).isEqualTo(pricesBefore);
  }

  @Test
  void aBackupSourceOnlyFillsTheMissingDaysAndKeepsTheDividendAdjustment() throws Exception {
    var voo = PriceSource.Yahoo.parse(fixture("yahoo-voo-1mo.json"));
    // El historial guardado termina 3 días antes del último dato de Yahoo, con un ajuste de dividendos de 0.99.
    var stored = voo.subList(0, voo.size() - 3).stream().map(b -> new Types.Bar(b.day(), b.close(), b.close() * 0.99)).toList();
    store.upsertBars("VOO", stored, "yahoo");
    var lastStored = stored.getLast().day();

    HttpFetcher http = new HttpFetcher() {
      public String get(String url, Map<String, String> h) throws IOException {
        if (url.contains("yahoo") || url.contains("twelvedata")) throw new IOException("HTTP 429");
        var sb = new StringBuilder("Date,Open,High,Low,Close,Volume\n");
        for (var b : voo) sb.append(b.day()).append(",1,1,1,").append(b.close()).append(",5\n");
        return sb.toString();
      }

      public String post(String url, String body, Map<String, String> h) {
        throw new UnsupportedOperationException();
      }
    };
    var chain = List.<PriceSource>of(new PriceSource.Yahoo(http, "https://yahoo"), new PriceSource.Stooq(http, "https://stooq"));
    var clock = Clock.fixed(voo.getLast().day().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    var series = new PriceService(chain, store, clock).load("VOO");

    assertThat(series.source()).isEqualTo("stooq");
    assertThat(series.stale()).isFalse();
    assertThat(series.bars().getLast().day()).isEqualTo(voo.getLast().day());
    assertThat(series.bars().stream().filter(b -> b.day().isAfter(lastStored)).count()).isEqualTo(3);
    var oldBar = series.bars().stream().filter(b -> b.day().equals(lastStored)).findFirst().orElseThrow();
    assertThat(oldBar.adjClose()).isCloseTo(oldBar.close() * 0.99, within(1e-6));
    var newBar = series.bars().getLast();
    assertThat(newBar.adjClose() / newBar.close()).isCloseTo(0.99, within(1e-6));
  }

  @Test
  void onlyOneRefreshRunsAtATime() throws Exception {
    var gate = new java.util.concurrent.CountDownLatch(1);
    var http = new Stub() {
      @Override
      public String get(String url, Map<String, String> h) throws IOException {
        try {
          gate.await(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return super.get(url, h);
      }
    };
    var service = new InvestService(store, http, "", "", "", "", "", Clock.systemUTC());
    var first = java.util.concurrent.CompletableFuture.supplyAsync(service::refresh);
    Thread.sleep(300);
    assertThat(service.status().running()).isTrue();
    assertThat(service.refresh()).isFalse();
    gate.countDown();
    assertThat(first.get()).isTrue();
    assertThat(service.status().running()).isFalse();
  }
}
