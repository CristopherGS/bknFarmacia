package com.sglabs.farmacia.invest;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProjectionStressEpisodesTest {
  static final LocalDate TODAY = LocalDate.of(2026, 10, 23);

  @Test
  void projectionWithoutReturnIsPlainArithmetic() {
    var r = Projection.toGoal(10000, 1000, 0, 100000, TODAY);
    assertThat(r.months()).isEqualTo(90);
    assertThat(r.eta()).isEqualTo(TODAY.plusMonths(90));
    assertThat(r.progressPct()).isCloseTo(10, within(1e-9));
  }

  @Test
  void returnsShortenTheWait() {
    assertThat(Projection.toGoal(10000, 1000, 0.12, 100000, TODAY).months()).isLessThan(90);
  }

  @Test
  void edgeCases() {
    assertThat(Projection.toGoal(120000, 1000, 0.06, 100000, TODAY).months()).isZero();
    assertThat(Projection.toGoal(120000, 1000, 0.06, 100000, TODAY).progressPct()).isEqualTo(100);
    assertThat(Projection.toGoal(5000, 0, 0, 100000, TODAY).months()).isNull(); // nunca llega
    assertThat(Projection.toGoal(5000, 100, 0.06, 0, TODAY).months()).isNull(); // sin meta
  }

  @Test
  void stressAppliesEachAssetClassShock() {
    var values = new LinkedHashMap<>(Map.of("VOO", 600.0, "BND", 200.0, "SGOV", 200.0));
    var classes = Map.of("VOO", "equity_us", "BND", "bonds", "SGOV", "cash");
    var results = StressTest.run(values, classes, Map.of("2008", Map.of("equity_us", -0.55, "bonds", 0.05, "cash", 0.0)));
    assertThat(results).hasSize(1);
    assertThat(results.getFirst().lossUsd()).isCloseTo(-320, within(1e-9)); // -330 + 10
    assertThat(results.getFirst().lossPct()).isCloseTo(-32, within(1e-9));
  }

  @Test
  void anUnknownClassIsTreatedAsUsEquities() {
    var results = StressTest.run(Map.of("ZZZ", 100.0), Map.of(), Map.of("x", Map.of("equity_us", -0.5)));
    assertThat(results.getFirst().lossUsd()).isCloseTo(-50, within(1e-9));
  }

  static LocalDate[] days(int n) {
    var d = new LocalDate[n];
    for (int i = 0; i < n; i++) d[i] = LocalDate.of(2020, 1, 1).plusDays(i * 30L);
    return d;
  }

  @Test
  void episodesGoFromPeakToTroughToRecovery() {
    var prices = new double[] {100, 110, 90, 100, 111, 95, 96};
    var eps = DrawdownEpisodes.episodes(days(prices.length), prices, 0.10);
    assertThat(eps).hasSize(2);
    assertThat(eps.get(0).depth()).isCloseTo(90.0 / 110 - 1, within(1e-9));
    assertThat(eps.get(0).recovered()).isNotNull(); // volvió a 111 > 110
    assertThat(eps.get(1).recovered()).isNull(); // la caída de 111 a 95 sigue abierta
  }

  @Test
  void statsCountEpisodesByThresholdAndMeasureMonths() {
    var prices = new double[] {100, 110, 90, 100, 111, 95, 96};
    var eps = DrawdownEpisodes.episodes(days(prices.length), prices, 0.10);
    var stats = DrawdownEpisodes.stats(eps, 10, 15);
    assertThat(stats.get(0).count()).isEqualTo(2);
    assertThat(stats.get(0).stillOpen()).isEqualTo(1);
    assertThat(stats.get(1).count()).isEqualTo(1); // solo la de -18%
    assertThat(stats.get(1).medianMonthsToBottom()).isCloseTo(30 / 30.44, within(1e-6));
    assertThat(stats.get(1).medianMonthsToRecover()).isCloseTo(90 / 30.44, within(1e-6)); // del máximo al nuevo máximo
    assertThat(stats.get(1).worstPct()).isCloseTo((90.0 / 110 - 1) * 100, within(1e-9));
  }

  @Test
  void noEpisodesWhenThePriceOnlyRises() {
    assertThat(DrawdownEpisodes.episodes(days(4), new double[] {1, 2, 3, 4}, 0.10)).isEmpty();
    assertThat(DrawdownEpisodes.stats(List.of(), 10).getFirst().count()).isZero();
  }
}
