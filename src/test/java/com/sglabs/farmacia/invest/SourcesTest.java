package com.sglabs.farmacia.invest;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class SourcesTest {
  static String fixture(String name) throws IOException {
    return Files.readString(Path.of("src/test/resources/invest/" + name));
  }

  @Test
  void yahooBarsCarryCloseAndDividendAdjustedClose() throws IOException {
    var bars = PriceSource.Yahoo.parse(fixture("yahoo-voo-1mo.json"));
    assertThat(bars).hasSizeGreaterThan(15);
    assertThat(bars).allSatisfy(b -> {
      assertThat(b.close()).isPositive();
      assertThat(b.adjClose()).isPositive().isLessThanOrEqualTo(b.close() * 1.0001);
    });
    assertThat(bars).isSortedAccordingTo(Comparator.comparing(Types.Bar::day));
  }

  @Test
  void yahooErrorsAndEmptyAnswersAreReportedNotSwallowed() {
    assertThatThrownBy(() -> PriceSource.Yahoo.parse("{\"chart\":{\"result\":null,\"error\":{\"code\":\"Not Found\",\"description\":\"No data found\"}}}"))
        .isInstanceOf(IOException.class).hasMessageContaining("No data found");
    assertThatThrownBy(() -> PriceSource.Yahoo.parse("{\"chart\":{\"result\":[{\"meta\":{},\"timestamp\":[],\"indicators\":{\"quote\":[{\"close\":[]}]}}]}}"))
        .isInstanceOf(IOException.class);
  }

  @Test
  void stooqSkipsBrokenRowsAndRejectsHtmlErrorPages() throws IOException {
    var bars = PriceSource.Stooq.parse("Date,Open,High,Low,Close,Volume\n2026-10-01,1,1,1,100.5,5\nbasura\n2026-10-02,1,1,1,101,5\n");
    assertThat(bars).hasSize(2);
    assertThatThrownBy(() -> PriceSource.Stooq.parse("<html>Exceeded the daily hits limit</html>")).isInstanceOf(IOException.class);
  }

  @Test
  void twelveDataIsOffWithoutAKeyAndNeverUsedForIndices() {
    var off = new PriceSource.TwelveData(null, "https://x", "");
    assertThat(off.symbolFor("VOO")).isNull();
    var on = new PriceSource.TwelveData(null, "https://x", "k");
    assertThat(on.symbolFor("VOO")).isEqualTo("VOO");
    assertThat(on.symbolFor("^GSPC")).isNull();
  }

  @Test
  void fredCsvSkipsMissingValuesMarkedWithADot() throws IOException {
    var obs = FredClient.parseCsv("observation_date,VIXCLS\n2026-09-21,14.87\n2026-09-22,.\n2026-09-23,15.1\n");
    assertThat(obs).extracting(Types.Obs::value).containsExactly(14.87, 15.1);
    assertThat(FredClient.parseCsv(fixture("fred-vix.csv"))).isNotEmpty();
  }

  @Test
  void fredApiJsonIsParsedAndMissingValuesAreSkipped() throws IOException {
    var obs = FredClient.parseApi("{\"observations\":[{\"date\":\"2026-09-21\",\"value\":\"4.25\"},{\"date\":\"2026-09-22\",\"value\":\".\"}]}");
    assertThat(obs).hasSize(1);
    assertThat(obs.getFirst().value()).isEqualTo(4.25);
  }

  @Test
  void fredFallsBackToThePublicCsvWhenTheKeyedApiFails() throws IOException {
    HttpFetcher http = new HttpFetcher() {
      public String get(String url, Map<String, String> h) throws IOException {
        if (url.contains("/fred/series/observations")) throw new IOException("HTTP 429");
        return "observation_date,VIXCLS\n2026-09-21,14.87\n";
      }

      public String post(String u, String b, Map<String, String> h) {
        throw new UnsupportedOperationException();
      }
    };
    var obs = new FredClient(http, "https://api", "https://csv", "key").fetch("VIXCLS", java.time.LocalDate.of(2026, 9, 1));
    assertThat(obs).hasSize(1);
  }

  @Test
  void banguatReferenceRateIsReadFromTheSoapAnswer() throws IOException {
    var fx = BanguatFx.parse(fixture("banguat-dia.xml"));
    assertThat(fx.gtqPerUsd()).isBetween(5.0, 12.0);
    assertThat(fx.source()).containsIgnoringCase("banguat");
  }

  @Test
  void banguatRejectsXmlWithExternalEntities() {
    String evil = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><x>&e;</x>";
    assertThatThrownBy(() -> BanguatFx.parse(evil)).isInstanceOf(IOException.class);
  }

  @Test
  void finnhubHeadlinesAreParsedAndFilteredByRelevance() throws IOException {
    long now = Instant.now().getEpochSecond();
    String body = "[{\"headline\":\"Fed holds rates steady as inflation cools\",\"url\":\"https://a/1\",\"source\":\"Reuters\",\"datetime\":" + now + ",\"summary\":\"\"},"
        + "{\"headline\":\"Celebrity chef opens new restaurant\",\"url\":\"https://a/2\",\"source\":\"X\",\"datetime\":" + now + ",\"summary\":\"\"},"
        + "{\"headline\":\"Fed holds rates steady as inflation cools\",\"url\":\"https://a/3\",\"source\":\"Y\",\"datetime\":" + now + ",\"summary\":\"\"}]";
    var all = FinnhubNews.parse("", body);
    assertThat(all).hasSize(3);
    var picked = NewsFilter.pick(all, Set.of("VOO"), 10);
    assertThat(picked).hasSize(1); // el titular repetido y el irrelevante se descartan
    assertThat(picked.getFirst().whyItMatters()).isNotBlank();
  }
}
