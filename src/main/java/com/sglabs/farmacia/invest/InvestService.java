package com.sglabs.farmacia.invest;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Actualiza datos de todas las fuentes (con respaldo y caché), arma el resumen del día, lo guarda y
 * manda avisos. Si una fuente falla, el resumen se arma con lo último guardado y lo dice en "sources".
 */
@Service
class InvestService {
  private static final Logger LOG = LoggerFactory.getLogger(InvestService.class);
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  record Status(boolean running, String startedAt, String finishedAt, String error) {}

  private final InvestStore store;
  private final PriceService prices;
  private final PriceSource yahoo;
  private final FredClient fred;
  private final BanguatFx banguat;
  private final FinnhubNews news;
  private final Telegram telegram;
  private final Clock clock;
  private final AtomicReference<Status> status = new AtomicReference<>(new Status(false, null, null, null));

  @Autowired
  InvestService(
      InvestStore store,
      @Value("${app.invest.fred-key:}") String fredKey,
      @Value("${app.invest.finnhub-token:}") String finnhub,
      @Value("${app.invest.twelvedata-key:}") String twelve,
      @Value("${app.invest.telegram-token:}") String tgToken,
      @Value("${app.invest.telegram-chat:}") String tgChat) {
    this(store, new HttpFetcher.Jdk(), fredKey, finnhub, twelve, tgToken, tgChat, Clock.systemUTC());
  }

  InvestService(InvestStore store, HttpFetcher http, String fredKey, String finnhub, String twelve, String tgToken, String tgChat, Clock clock) {
    this.store = store;
    this.clock = clock;
    var chain = new ArrayList<PriceSource>();
    this.yahoo = new PriceSource.Yahoo(http, "https://query1.finance.yahoo.com");
    chain.add(yahoo);
    chain.add(new PriceSource.TwelveData(http, "https://api.twelvedata.com", twelve));
    chain.add(new PriceSource.Stooq(http, "https://stooq.com"));
    this.prices = new PriceService(chain, store, clock);
    this.fred = new FredClient(http, "https://api.stlouisfed.org", "https://fred.stlouisfed.org", fredKey);
    this.banguat = new BanguatFx(http, "https://www.banguat.gob.gt/variables/ws/TipoCambio.asmx");
    this.news = new FinnhubNews(http, "https://finnhub.io", finnhub);
    this.telegram = new Telegram(http, "https://api.telegram.org", tgToken, tgChat);
  }

  Status status() {
    return status.get();
  }

  /** Solo una actualización a la vez. Devuelve false si ya hay una en curso. */
  boolean refresh() {
    var cur = status.get();
    if (cur.running()) return false;
    if (!status.compareAndSet(cur, new Status(true, clock.instant().toString(), null, null))) return false;
    String error = null;
    try {
      doRefresh();
    } catch (Exception e) {
      LOG.error("La actualización del portafolio falló", e);
      error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    } finally {
      status.set(new Status(false, status.get().startedAt(), clock.instant().toString(), error));
    }
    return true;
  }

  private void doRefresh() throws Exception {
    LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    var cfg = store.config();
    var positions = store.positions();
    var sources = new LinkedHashMap<String, Object>();

    var tickers = new LinkedHashSet<String>();
    tickers.add(SnapshotBuilder.SP);
    tickers.addAll(cfg.target().keySet());
    positions.forEach(p -> tickers.add(p.ticker()));
    var bars = new LinkedHashMap<String, List<Types.Bar>>();
    var priceStatus = new LinkedHashMap<String, Object>();
    for (var t : tickers) {
      var s = prices.load(t);
      bars.put(t, s.bars());
      priceStatus.put(t, sourceInfo(s.source(), s.asOf(), s.stale(), s.error()));
    }
    sources.put("prices", priceStatus);

    var macro = new LinkedHashMap<String, List<Types.Obs>>();
    var macroStatus = new LinkedHashMap<String, Object>();
    // Las siete series se piden en paralelo: una fuente lenta no debe hacer esperar a las demás.
    var pool = java.util.concurrent.Executors.newFixedThreadPool(FredClient.SERIES.size());
    try {
      var jobs = new LinkedHashMap<String, java.util.concurrent.CompletableFuture<String>>();
      for (var id : FredClient.SERIES) jobs.put(id, java.util.concurrent.CompletableFuture.supplyAsync(() -> fetchMacro(id, today), pool));
      for (var e : jobs.entrySet()) {
        var id = e.getKey();
        var info = e.getValue().join(); // "" si fue bien; si no, el motivo; el prefijo "respaldo:" indica otra fuente
        var o = store.obs(id);
        macro.put(id, o);
        LocalDate asOf = o.isEmpty() ? null : o.getLast().day();
        // Las series mensuales (inflación, tasa de la Fed) se retrasan semanas por naturaleza: no son "atrasadas".
        int slack = List.of("FEDFUNDS", "CPIAUCSL").contains(id) ? 75 : 10;
        boolean stale = asOf == null || java.time.temporal.ChronoUnit.DAYS.between(asOf, today) > slack;
        macroStatus.put(id, sourceInfo(info.startsWith("respaldo:") ? "yahoo (respaldo)" : "fred", asOf, stale, info.startsWith("respaldo:") ? "" : info));
      }
    } finally {
      pool.shutdown();
    }
    sources.put("macro", macroStatus);

    Types.Fx fx;
    String fxErr = "";
    try {
      fx = banguat.today();
      store.saveFx(fx);
    } catch (Exception e) {
      fxErr = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      var saved = store.latestFx();
      fx = saved != null ? new Types.Fx(saved.gtqPerUsd(), saved.asOf(), saved.source(), true) : new Types.Fx(cfg.fxFallbackGtqPerUsd(), today, "configuración", true);
    }
    sources.put("fx", sourceInfo(fx.source(), fx.asOf(), fx.stale(), fxErr));

    String newsErr = "";
    if (news.enabled()) {
      try {
        var all = new ArrayList<>(news.market());
        for (var t : tickers) if (!t.startsWith("^")) all.addAll(news.company(t, today.minusDays(7), today));
        store.upsertNews(all);
      } catch (Exception e) {
        newsErr = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      }
    } else newsErr = "Sin FINNHUB_TOKEN: no hay noticias.";
    sources.put("news", sourceInfo("finnhub", null, !news.enabled() || !newsErr.isEmpty(), newsErr));
    var picked = NewsFilter.pick(store.recentNews(7), tickers, 8);

    var sp = bars.getOrDefault(SnapshotBuilder.SP, List.of());
    var recovery = sp.size() < 2 ? List.<DrawdownEpisodes.Stat>of() : DrawdownEpisodes.stats(
        DrawdownEpisodes.episodes(sp.stream().map(Types.Bar::day).toArray(LocalDate[]::new), sp.stream().mapToDouble(Types.Bar::close).toArray(), 0.10), 10, 20, 30);

    var snap = SnapshotBuilder.build(new SnapshotBuilder.Inputs(cfg, positions, store.contributions(), bars, macro, fx, picked, recovery, sources, today));

    var prev = new HashMap<String, String>();
    for (var k : List.of("semaforo", "rebalance", "dd_level")) prev.put(k, store.state(k, null));
    var alerts = AlertRules.evaluate(prev, snap);
    snap.put("alerts", alerts.messages());
    store.saveSnapshot(today, JSON.writeValueAsString(snap));
    alerts.newState().forEach(store::setState);
    boolean first = prev.get("semaforo") == null; // primera corrida: solo fija el estado, no avisa de lo que ya estaba así
    if (!first) alerts.messages().forEach(telegram::send);
  }

  /** Series que Yahoo también publica: sirven de respaldo cuando FRED no responde y no hay llave. */
  private static final Map<String, String> YAHOO_BACKUP = Map.of("VIXCLS", "^VIX", "DGS10", "^TNX");

  /** Trae una serie macro y la guarda. Devuelve "" si todo bien, "respaldo: …" si usó Yahoo, o el error. */
  private String fetchMacro(String id, LocalDate today) {
    LocalDate last = store.lastObsDay(id);
    LocalDate from = last == null ? today.minusYears(3) : last.minusDays(45);
    try {
      store.upsertObs(id, fred.fetch(id, from));
      return "";
    } catch (Exception e) {
      String err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      LOG.warn("FRED {} falló: {}", id, err);
      String backup = YAHOO_BACKUP.get(id);
      if (backup == null) return err;
      try {
        var obs = yahoo.fetch(backup, last == null ? "3y" : "1y").stream().map(b -> new Types.Obs(b.day(), b.close())).toList();
        store.upsertObs(id, obs);
        return "respaldo: " + err;
      } catch (Exception e2) {
        return err + "; Yahoo: " + e2.getMessage();
      }
    }
  }

  private static Map<String, Object> sourceInfo(String source, LocalDate asOf, boolean stale, String error) {
    var m = new LinkedHashMap<String, Object>();
    m.put("source", source);
    m.put("asOf", asOf == null ? null : asOf.toString());
    m.put("stale", stale);
    m.put("error", error == null ? "" : error);
    return m;
  }
}
