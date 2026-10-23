package com.sglabs.farmacia.invest;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class SnapshotBuilderTest {
  static final LocalDate TODAY = LocalDate.of(2026, 6, 30);

  /** Precios sintéticos: crecen 0.05% al día con un vaivén para que haya volatilidad. */
  static List<Types.Bar> bars(double start, double drift, int days) {
    var out = new ArrayList<Types.Bar>();
    double p = start;
    for (int i = 0; i < days; i++) {
      p *= 1 + drift + (i % 2 == 0 ? 0.004 : -0.004);
      out.add(new Types.Bar(TODAY.minusDays(days - 1 - i), p, p));
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> map(Object o) {
    return (Map<String, Object>) o;
  }

  Map<String, Object> build(List<Types.Position> positions, int days, Map<String, List<Types.Obs>> macro) {
    var bars = new HashMap<String, List<Types.Bar>>();
    bars.put("^GSPC", bars(5000, 0.0005, days));
    bars.put("VOO", bars(450, 0.0005, days));
    bars.put("VXUS", bars(60, 0.0002, days));
    bars.put("BND", bars(72, 0.0001, days));
    bars.put("SGOV", bars(100, 0.0001, days));
    return SnapshotBuilder.build(new SnapshotBuilder.Inputs(InvestConfig.defaults(), positions, List.of(), bars, macro, new Types.Fx(7.70, TODAY, "banguat", false), List.of(), List.of(), Map.of(), TODAY));
  }

  List<Types.Position> balanced(LocalDate on) {
    return List.of(
        new Types.Position(1, "VOO", 10, 450, on),
        new Types.Position(2, "VXUS", 20, 60, on),
        new Types.Position(3, "BND", 30, 72, on),
        new Types.Position(4, "SGOV", 5, 100, on));
  }

  @Test
  void withoutPositionsItStillBuildsAMarketViewAndAGuide() {
    var s = build(List.of(), 60, Map.of());
    assertThat(map(s.get("portfolio")).get("hasPositions")).isEqualTo(false);
    assertThat(map(s.get("portfolio")).get("valueUsd")).isEqualTo(0.0);
    assertThat(map(s.get("market"))).containsKey("indicators");
    assertThat((String) s.get("narrative")).isNotBlank();
  }

  @Test
  void weightsAddUpAndEachHoldingShowsItsDeviationFromTheTarget() {
    var s = build(balanced(TODAY.minusDays(100)), 120, Map.of());
    var holdings = (List<Map<String, Object>>) map(s.get("portfolio")).get("holdings");
    assertThat(holdings).hasSize(4);
    double sum = holdings.stream().mapToDouble(h -> ((Number) h.get("weightPct")).doubleValue()).sum();
    assertThat(sum).isCloseTo(100, within(0.1));
    for (var h : holdings) {
      double dev = ((Number) h.get("deviationPts")).doubleValue();
      assertThat(dev).isCloseTo(((Number) h.get("weightPct")).doubleValue() - ((Number) h.get("targetPct")).doubleValue(), within(0.02));
    }
  }

  @Test
  void rebalanceBuysUseOnlyTheMonthlyContributionAndNeverSell() {
    // Casi todo en VOO: muy por encima de su 50%.
    var heavy = List.of(new Types.Position(1, "VOO", 100, 450, TODAY.minusDays(100)), new Types.Position(2, "BND", 5, 72, TODAY.minusDays(100)));
    var s = build(heavy, 120, Map.of());
    var portfolio = map(s.get("portfolio"));
    assertThat(map(portfolio.get("rebalance")).get("needed")).isEqualTo(true);
    var buys = (List<Map<String, Object>>) map(s.get("calendar")).get("buys");
    assertThat(buys).isNotEmpty();
    assertThat(buys).noneMatch(b -> "VOO".equals(b.get("ticker")));
    double gtq = buys.stream().mapToDouble(b -> ((Number) b.get("gtq")).doubleValue()).sum();
    assertThat(gtq).isCloseTo(2000, within(1.0));
  }

  @Test
  void concentrationIsFlaggedWhenOneEtfDominates() {
    var s = build(List.of(new Types.Position(1, "VOO", 100, 450, TODAY.minusDays(100))), 120, Map.of());
    var conc = (List<?>) map(s.get("portfolio")).get("concentration");
    assertThat(conc).hasSize(1);
  }

  @Test
  void riskMetricsNeedEnoughHistoryAndExplainWhenTheyDoNot() {
    var few = map(map(build(balanced(TODAY.minusDays(10)), 60, Map.of()).get("portfolio")).get("risk"));
    assertThat(few.get("enoughData")).isEqualTo(false);
    assertThat((String) few.get("note")).contains("30 días");
    assertThat(few).doesNotContainKey("sharpe");

    var many = map(map(build(balanced(TODAY.minusDays(110)), 150, Map.of()).get("portfolio")).get("risk"));
    assertThat(many.get("enoughData")).isEqualTo(true);
    assertThat(many).containsKeys("volAnnPct", "maxDrawdownPct", "sharpe", "beta");
    assertThat(((Number) many.get("volAnnPct")).doubleValue()).isPositive();
  }

  @Test
  void chartsLineUpDateByDateAndTheContributedLineNeverShrinks() {
    var s = build(balanced(TODAY.minusDays(100)), 120, Map.of());
    var c = map(s.get("charts"));
    int n = ((List<?>) c.get("dates")).size();
    assertThat(n).isGreaterThan(50);
    for (var k : List.of("portfolioUsd", "sp500Usd", "contributedUsd", "drawdownPct")) assertThat((List<?>) c.get(k)).hasSize(n);
    var contributed = (List<Double>) c.get("contributedUsd");
    for (int i = 1; i < contributed.size(); i++) assertThat(contributed.get(i)).isGreaterThanOrEqualTo(contributed.get(i - 1));
  }

  @Test
  void stressTestReportsLossesInQuetzales() {
    var s = build(balanced(TODAY.minusDays(100)), 120, Map.of());
    var stress = (List<Map<String, Object>>) map(s.get("portfolio")).get("stress");
    assertThat(stress).hasSize(3);
    assertThat(stress).allSatisfy(x -> assertThat(((Number) x.get("lossGtq")).doubleValue()).isNotZero());
  }

  @Test
  void aVixSpikeTurnsTheTrafficLightRed() {
    var macro = Map.of("VIXCLS", List.of(new Types.Obs(TODAY, 38.0)));
    var s = build(balanced(TODAY.minusDays(100)), 120, macro);
    assertThat(map(s.get("market")).get("overall")).isEqualTo("rojo");
  }

  // ---- alertas ----

  @Test
  void alertsFireOnlyWhenSomethingChanges() {
    var red = new HashMap<String, Object>();
    red.put("market", Map.of("overall", "rojo", "summary", "VIX alto."));
    red.put("portfolio", Map.of("rebalance", Map.of("needed", true)));
    red.put("calendar", Map.of("buys", List.of(Map.of("ticker", "BND", "gtq", 1500.0))));
    red.put("plan", Map.of("drawdownPct", -21.0, "message", "Plan activo."));

    var first = AlertRules.evaluate(Map.of("semaforo", "verde", "rebalance", "false", "dd_level", "0"), red);
    assertThat(first.messages()).hasSize(3);
    assertThat(first.messages().get(1)).contains("BND Q1500");
    assertThat(first.newState()).containsEntry("semaforo", "rojo").containsEntry("dd_level", "20");

    var again = AlertRules.evaluate(first.newState(), red);
    assertThat(again.messages()).isEmpty();

    // Una caída más profunda sí avisa de nuevo, pero un rebote no.
    red.put("plan", Map.of("drawdownPct", -31.0, "message", "Más profunda."));
    assertThat(AlertRules.evaluate(again.newState(), red).messages()).hasSize(1);
    red.put("plan", Map.of("drawdownPct", -5.0, "message", "Rebote."));
    assertThat(AlertRules.evaluate(again.newState(), red).messages()).isEmpty();
  }
}
