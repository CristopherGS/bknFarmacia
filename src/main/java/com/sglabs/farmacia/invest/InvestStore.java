package com.sglabs.farmacia.invest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Acceso a las tablas invest_*. Todo es del dueño: no hay filtros por usuario porque es un solo inversor. */
@Component
class InvestStore {
  private final JdbcTemplate db;
  private final ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  InvestStore(JdbcTemplate db) {
    this.db = db;
  }

  // ---- configuración ----

  InvestConfig config() {
    var rows = db.queryForList("SELECT config::text FROM invest_config WHERE id=1", String.class);
    if (rows.isEmpty()) return InvestConfig.defaults();
    try {
      return json.readValue(rows.getFirst(), InvestConfig.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("La configuración guardada no se pudo leer", e);
    }
  }

  void saveConfig(InvestConfig c) {
    try {
      db.update(
          "INSERT INTO invest_config(id,config) VALUES (1,?::jsonb) ON CONFLICT (id) DO UPDATE SET"
              + " config=EXCLUDED.config,updated_at=CURRENT_TIMESTAMP",
          json.writeValueAsString(c));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  // ---- posiciones y aportes ----

  List<Types.Position> positions() {
    return db.query(
        "SELECT id,ticker,shares,avg_price_usd,bought_on FROM invest_positions ORDER BY bought_on,id",
        (r, n) -> new Types.Position(r.getLong(1), r.getString(2), r.getDouble(3), r.getDouble(4), r.getDate(5).toLocalDate()));
  }

  long addPosition(String ticker, double shares, double avg, LocalDate on) {
    return db.queryForObject(
        "INSERT INTO invest_positions(ticker,shares,avg_price_usd,bought_on) VALUES (?,?,?,?) RETURNING id",
        Long.class, ticker, shares, avg, Date.valueOf(on));
  }

  boolean updatePosition(long id, String ticker, double shares, double avg, LocalDate on) {
    return db.update("UPDATE invest_positions SET ticker=?,shares=?,avg_price_usd=?,bought_on=? WHERE id=?", ticker, shares, avg, Date.valueOf(on), id) > 0;
  }

  boolean deletePosition(long id) {
    return db.update("DELETE FROM invest_positions WHERE id=?", id) > 0;
  }

  List<Types.Contribution> contributions() {
    return db.query(
        "SELECT id,day,gtq,usd,fx FROM invest_contributions ORDER BY day,id",
        (r, n) -> new Types.Contribution(r.getLong(1), r.getDate(2).toLocalDate(), r.getDouble(3), r.getDouble(4), r.getDouble(5)));
  }

  long addContribution(LocalDate day, double gtq, double usd, double fx) {
    return db.queryForObject("INSERT INTO invest_contributions(day,gtq,usd,fx) VALUES (?,?,?,?) RETURNING id", Long.class, Date.valueOf(day), gtq, usd, fx);
  }

  boolean deleteContribution(long id) {
    return db.update("DELETE FROM invest_contributions WHERE id=?", id) > 0;
  }

  // ---- precios ----

  LocalDate lastBarDay(String ticker) {
    var d = db.queryForObject("SELECT MAX(day) FROM invest_prices WHERE ticker=?", Date.class, ticker);
    return d == null ? null : d.toLocalDate();
  }

  /** Relación adj_close/close de la última fila guardada, para ajustar las filas de una fuente de respaldo. */
  Double lastAdjRatio(String ticker) {
    var rows = db.queryForList("SELECT adj_close/close FROM invest_prices WHERE ticker=? ORDER BY day DESC LIMIT 1", Double.class, ticker);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  void upsertBars(String ticker, List<Types.Bar> bars, String source) {
    db.batchUpdate(
        "INSERT INTO invest_prices(ticker,day,close,adj_close,source) VALUES (?,?,?,?,?) ON CONFLICT"
            + " (ticker,day) DO UPDATE SET close=EXCLUDED.close,adj_close=EXCLUDED.adj_close,source=EXCLUDED.source",
        bars, 1000, (ps, b) -> {
          ps.setString(1, ticker);
          ps.setDate(2, Date.valueOf(b.day()));
          ps.setDouble(3, b.close());
          ps.setDouble(4, b.adjClose());
          ps.setString(5, source);
        });
  }

  List<Types.Bar> bars(String ticker) {
    return db.query("SELECT day,close,adj_close FROM invest_prices WHERE ticker=? ORDER BY day", (r, n) -> new Types.Bar(r.getDate(1).toLocalDate(), r.getDouble(2), r.getDouble(3)), ticker);
  }

  // ---- macro y tipo de cambio ----

  LocalDate lastObsDay(String series) {
    var d = db.queryForObject("SELECT MAX(day) FROM invest_macro WHERE series=?", Date.class, series);
    return d == null ? null : d.toLocalDate();
  }

  void upsertObs(String series, List<Types.Obs> obs) {
    db.batchUpdate("INSERT INTO invest_macro(series,day,value) VALUES (?,?,?) ON CONFLICT (series,day) DO UPDATE SET value=EXCLUDED.value", obs, 1000, (ps, o) -> {
      ps.setString(1, series);
      ps.setDate(2, Date.valueOf(o.day()));
      ps.setDouble(3, o.value());
    });
  }

  List<Types.Obs> obs(String series) {
    return db.query("SELECT day,value FROM invest_macro WHERE series=? ORDER BY day", (r, n) -> new Types.Obs(r.getDate(1).toLocalDate(), r.getDouble(2)), series);
  }

  void saveFx(Types.Fx fx) {
    db.update("INSERT INTO invest_fx(day,gtq_per_usd,source) VALUES (?,?,?) ON CONFLICT (day) DO UPDATE SET gtq_per_usd=EXCLUDED.gtq_per_usd,source=EXCLUDED.source", Date.valueOf(fx.asOf()), fx.gtqPerUsd(), fx.source());
  }

  Types.Fx latestFx() {
    var rows = db.query("SELECT day,gtq_per_usd,source FROM invest_fx ORDER BY day DESC LIMIT 1", (r, n) -> new Types.Fx(r.getDouble(2), r.getDate(1).toLocalDate(), r.getString(3), false));
    return rows.isEmpty() ? null : rows.getFirst();
  }

  // ---- resúmenes diarios ----

  void saveSnapshot(LocalDate day, String jsonText) {
    db.update("INSERT INTO invest_snapshots(day,data) VALUES (?,?::jsonb) ON CONFLICT (day) DO UPDATE SET data=EXCLUDED.data,generated_at=CURRENT_TIMESTAMP", Date.valueOf(day), jsonText);
  }

  Optional<String> latestSnapshot() {
    return db.queryForList("SELECT data::text FROM invest_snapshots ORDER BY day DESC LIMIT 1", String.class).stream().findFirst();
  }

  /** Evolución diaria: valor y semáforo de cada resumen guardado. */
  List<Map<String, Object>> history(LocalDate since) {
    return db.queryForList(
        "SELECT day, (data->'portfolio'->>'valueUsd')::float AS value_usd, (data->'portfolio'->>'valueGtq')::float AS value_gtq,"
            + " (data->'portfolio'->>'totalGainPct')::float AS gain_pct, data->'market'->>'overall' AS semaforo,"
            + " (data->'market'->'sp500'->>'price')::float AS sp500 FROM invest_snapshots WHERE day>=? ORDER BY day",
        Date.valueOf(since));
  }

  // ---- noticias ----

  void upsertNews(List<Types.News> news) {
    db.batchUpdate("INSERT INTO invest_news(url,ticker,title,source,published_at,summary) VALUES (?,?,?,?,?,?) ON CONFLICT (url) DO NOTHING", news, 500, (ps, n) -> {
      ps.setString(1, n.url());
      ps.setString(2, n.ticker());
      ps.setString(3, n.title());
      ps.setString(4, n.source() == null ? "" : n.source());
      ps.setTimestamp(5, Timestamp.from(n.publishedAt()));
      ps.setString(6, n.summary() == null ? "" : (n.summary().length() > 690 ? n.summary().substring(0, 690) : n.summary()));
    });
  }

  List<Types.News> recentNews(int days) {
    return db.query(
        "SELECT ticker,title,url,source,published_at,summary FROM invest_news WHERE published_at > CURRENT_TIMESTAMP - (? * interval '1 day') ORDER BY published_at DESC LIMIT 300",
        (r, n) -> new Types.News(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getTimestamp(5).toInstant(), r.getString(6)),
        days);
  }

  // ---- estado de alertas ----

  String state(String key, String fallback) {
    var rows = db.queryForList("SELECT value FROM invest_state WHERE key=?", String.class, key);
    return rows.isEmpty() ? fallback : rows.getFirst();
  }

  void setState(String key, String value) {
    db.update("INSERT INTO invest_state(key,value) VALUES (?,?) ON CONFLICT (key) DO UPDATE SET value=EXCLUDED.value,updated_at=CURRENT_TIMESTAMP", key, value);
  }
}
