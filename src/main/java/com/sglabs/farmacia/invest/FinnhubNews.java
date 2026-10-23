package com.sglabs.farmacia.invest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** Titulares de Finnhub (plan gratis): del mercado en general y de tus tickers. */
final class FinnhubNews {
  private static final ObjectMapper JSON = new ObjectMapper();

  private final HttpFetcher http;
  private final String base;
  private final String token;

  FinnhubNews(HttpFetcher http, String base, String token) {
    this.http = http;
    this.base = base;
    this.token = token;
  }

  boolean enabled() {
    return token != null && !token.isBlank();
  }

  List<Types.News> market() throws IOException {
    return parse("", http.get(base + "/api/v1/news?category=general&token=" + token, Map.of()));
  }

  List<Types.News> company(String ticker, LocalDate from, LocalDate to) throws IOException {
    return parse(ticker, http.get(base + "/api/v1/company-news?symbol=" + ticker + "&from=" + from + "&to=" + to + "&token=" + token, Map.of()));
  }

  static List<Types.News> parse(String ticker, String body) throws IOException {
    JsonNode root = JSON.readTree(body);
    if (!root.isArray()) throw new IOException("Finnhub: respuesta inesperada");
    var out = new ArrayList<Types.News>();
    for (JsonNode n : root) {
      String title = n.path("headline").asText("").strip();
      String url = n.path("url").asText("").strip();
      if (title.isEmpty() || url.isEmpty() || url.length() > 480) continue;
      out.add(new Types.News(ticker, title.length() > 390 ? title.substring(0, 390) : title, url, n.path("source").asText(""), Instant.ofEpochSecond(n.path("datetime").asLong()), n.path("summary").asText("").strip()));
    }
    return out;
  }
}
