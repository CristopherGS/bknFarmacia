package com.sglabs.farmacia.invest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;

/**
 * Series macro de FRED. Con llave usa la API oficial; sin llave (o si la API falla) usa el CSV público
 * de la misma serie, que no necesita llave.
 */
final class FredClient {
  static final List<String> SERIES = List.of("VIXCLS", "DGS10", "DGS2", "T10Y2Y", "BAMLH0A0HYM2", "FEDFUNDS", "CPIAUCSL");
  private static final ObjectMapper JSON = new ObjectMapper();

  private final HttpFetcher http;
  private final String apiBase;
  private final String csvBase;
  private final String key;

  FredClient(HttpFetcher http, String apiBase, String csvBase, String key) {
    this.http = http;
    this.apiBase = apiBase;
    this.csvBase = csvBase;
    this.key = key;
  }

  List<Types.Obs> fetch(String series, LocalDate from) throws IOException {
    if (key != null && !key.isBlank()) {
      try {
        return parseApi(http.get(apiBase + "/fred/series/observations?series_id=" + series + "&api_key=" + key + "&file_type=json&observation_start=" + from, Map.of()));
      } catch (IOException e) {
        // la API puede fallar o agotar su límite: el CSV público sirve de respaldo
      }
    }
    return parseCsv(http.get(csvBase + "/graph/fredgraph.csv?id=" + series + "&cosd=" + from, Map.of()));
  }

  static List<Types.Obs> parseApi(String body) throws IOException {
    JsonNode root = JSON.readTree(body);
    if (root.has("error_message")) throw new IOException("FRED: " + root.path("error_message").asText());
    var out = new ArrayList<Types.Obs>();
    for (JsonNode o : root.path("observations")) addIfNumber(out, o.path("date").asText(), o.path("value").asText());
    return out;
  }

  static List<Types.Obs> parseCsv(String csv) throws IOException {
    var lines = csv.strip().split("\\R");
    if (lines.length < 1 || !lines[0].contains(",")) throw new IOException("FRED: CSV no válido");
    var out = new ArrayList<Types.Obs>();
    for (int i = 1; i < lines.length; i++) {
      var p = lines[i].split(",", -1);
      if (p.length >= 2) addIfNumber(out, p[0].trim(), p[1].trim());
    }
    return out;
  }

  /** FRED marca los días sin dato con "." o vacío: se omiten. */
  private static void addIfNumber(List<Types.Obs> out, String date, String value) {
    try {
      out.add(new Types.Obs(LocalDate.parse(date), Double.parseDouble(value)));
    } catch (RuntimeException e) {
      // sin dato
    }
  }
}
