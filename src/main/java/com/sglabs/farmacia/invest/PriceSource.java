package com.sglabs.farmacia.invest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/** Una fuente de precios diarios. Si no cubre el símbolo, devuelve null en `symbolFor`. */
interface PriceSource {
  String name();

  /** Símbolo en esa fuente, o null si no la usamos para este activo. */
  String symbolFor(String ticker);

  /** @param range cuánto historial pedir: "max", "1y" o "3mo" (las fuentes que no lo soportan envían todo). */
  List<Types.Bar> fetch(String ticker, String range) throws IOException;

  // ---- Yahoo Finance (la misma fuente que usa yfinance; sin llave) ----

  final class Yahoo implements PriceSource {
    private final HttpFetcher http;
    private final String base;
    private static final ObjectMapper JSON = new ObjectMapper();

    Yahoo(HttpFetcher http, String base) {
      this.http = http;
      this.base = base;
    }

    public String name() {
      return "yahoo";
    }

    public String symbolFor(String ticker) {
      return ticker;
    }

    public List<Types.Bar> fetch(String ticker, String range) throws IOException {
      // range=max devuelve barras trimestrales para el historial viejo; pedir desde el año 0 sí da diarias.
      String span = range.equals("max") ? "period1=0&period2=" + java.time.Instant.now().getEpochSecond() : "range=" + range;
      String url = base + "/v8/finance/chart/" + java.net.URLEncoder.encode(ticker, java.nio.charset.StandardCharsets.UTF_8) + "?" + span + "&interval=1d&events=div%7Csplit";
      return parse(http.get(url, Map.of("Accept", "application/json")));
    }

    static List<Types.Bar> parse(String body) throws IOException {
      JsonNode root = JSON.readTree(body).path("chart");
      if (!root.path("error").isMissingNode() && !root.path("error").isNull())
        throw new IOException("Yahoo: " + root.path("error").path("description").asText("error"));
      JsonNode res = root.path("result").path(0);
      if (res.isMissingNode()) throw new IOException("Yahoo: respuesta sin resultados");
      JsonNode ts = res.path("timestamp");
      JsonNode close = res.path("indicators").path("quote").path(0).path("close");
      JsonNode adj = res.path("indicators").path("adjclose").path(0).path("adjclose");
      int offset = res.path("meta").path("gmtoffset").asInt(0);
      var out = new ArrayList<Types.Bar>();
      for (int i = 0; i < ts.size(); i++) {
        JsonNode c = close.path(i);
        if (c.isNull() || c.isMissingNode()) continue; // día sin cotización
        LocalDate day = Instant.ofEpochSecond(ts.path(i).asLong()).atOffset(ZoneOffset.ofTotalSeconds(offset)).toLocalDate();
        JsonNode a = adj.path(i);
        out.add(new Types.Bar(day, c.asDouble(), a.isNumber() ? a.asDouble() : c.asDouble()));
      }
      if (out.isEmpty()) throw new IOException("Yahoo: sin datos para el símbolo");
      return out;
    }
  }

  // ---- Stooq (CSV sin llave) ----

  final class Stooq implements PriceSource {
    private final HttpFetcher http;
    private final String base;

    Stooq(HttpFetcher http, String base) {
      this.http = http;
      this.base = base;
    }

    public String name() {
      return "stooq";
    }

    public String symbolFor(String ticker) {
      if (ticker.equals("^GSPC")) return "^spx";
      return ticker.startsWith("^") ? null : ticker.toLowerCase(Locale.ROOT) + ".us";
    }

    public List<Types.Bar> fetch(String ticker, String range) throws IOException {
      return parse(http.get(base + "/q/d/l/?s=" + symbolFor(ticker) + "&i=d", Map.of()));
    }

    static List<Types.Bar> parse(String csv) throws IOException {
      var lines = csv.strip().split("\\R");
      if (lines.length < 2 || !lines[0].toLowerCase(Locale.ROOT).startsWith("date")) throw new IOException("Stooq: sin datos");
      var out = new ArrayList<Types.Bar>();
      for (int i = 1; i < lines.length; i++) {
        var p = lines[i].split(",");
        if (p.length < 5) continue;
        try {
          double c = Double.parseDouble(p[4]);
          out.add(new Types.Bar(LocalDate.parse(p[0]), c, c)); // Stooq no separa el ajuste por dividendos
        } catch (RuntimeException e) {
          // fila dañada: se ignora
        }
      }
      if (out.isEmpty()) throw new IOException("Stooq: sin filas válidas");
      return out;
    }
  }

  // ---- Twelve Data (plan gratis, con llave) ----

  final class TwelveData implements PriceSource {
    private final HttpFetcher http;
    private final String base;
    private final String key;
    private static final ObjectMapper JSON = new ObjectMapper();

    TwelveData(HttpFetcher http, String base, String key) {
      this.http = http;
      this.base = base;
      this.key = key;
    }

    public String name() {
      return "twelvedata";
    }

    /** Sin llave no se usa; y no se usa para índices: un ETF como proxy mezclaría escalas distintas. */
    public String symbolFor(String ticker) {
      return key == null || key.isBlank() || ticker.startsWith("^") ? null : ticker;
    }

    public List<Types.Bar> fetch(String ticker, String range) throws IOException {
      return parse(http.get(base + "/time_series?symbol=" + ticker + "&interval=1day&outputsize=5000&apikey=" + key, Map.of()));
    }

    static List<Types.Bar> parse(String body) throws IOException {
      JsonNode root = JSON.readTree(body);
      if ("error".equals(root.path("status").asText())) throw new IOException("Twelve Data: " + root.path("message").asText("error"));
      var out = new ArrayList<Types.Bar>();
      for (JsonNode v : root.path("values")) {
        try {
          double c = Double.parseDouble(v.path("close").asText());
          out.add(new Types.Bar(LocalDate.parse(v.path("datetime").asText().substring(0, 10)), c, c));
        } catch (RuntimeException e) {
          // fila dañada
        }
      }
      if (out.isEmpty()) throw new IOException("Twelve Data: sin datos");
      out.sort(Comparator.comparing(Types.Bar::day)); // llegan del más nuevo al más viejo
      return out;
    }
  }
}
