package com.sglabs.farmacia.invest;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class MarketSignalsPlanNarrativeTest {
  static MarketSignals.Inputs inputs(Double vix, Double dd, Double price, Double sma, Double curve, Double hy, Double hyCh) {
    return new MarketSignals.Inputs(vix, dd, price, sma, curve, hy, hyCh);
  }

  static String state(MarketSignals.Result r, String id) {
    return r.indicators().stream().filter(i -> i.id().equals(id)).findFirst().orElseThrow().state();
  }

  @Test
  void aCalmMarketIsGreen() {
    var r = MarketSignals.evaluate(inputs(14.0, -2.0, 5500.0, 5300.0, 0.3, 3.2, 0.0));
    assertThat(r.overall()).isEqualTo("verde");
    assertThat(r.indicators()).hasSize(5).allMatch(i -> i.state().equals("verde"));
    assertThat(r.indicators()).allMatch(i -> !i.means().isBlank() && !i.history().isBlank());
  }

  @Test
  void vixThresholdsMatchTheRules() {
    assertThat(state(MarketSignals.evaluate(inputs(19.9, null, null, null, null, null, null)), "vix")).isEqualTo("verde");
    assertThat(state(MarketSignals.evaluate(inputs(20.0, null, null, null, null, null, null)), "vix")).isEqualTo("amarillo");
    assertThat(state(MarketSignals.evaluate(inputs(30.0, null, null, null, null, null, null)), "vix")).isEqualTo("amarillo");
    assertThat(state(MarketSignals.evaluate(inputs(30.1, null, null, null, null, null, null)), "vix")).isEqualTo("rojo");
  }

  @Test
  void drawdownThresholdsMatchTheRules() {
    assertThat(state(MarketSignals.evaluate(inputs(null, -9.9, null, null, null, null, null)), "sp500_drawdown")).isEqualTo("verde");
    assertThat(state(MarketSignals.evaluate(inputs(null, -10.0, null, null, null, null, null)), "sp500_drawdown")).isEqualTo("amarillo");
    assertThat(state(MarketSignals.evaluate(inputs(null, -20.0, null, null, null, null, null)), "sp500_drawdown")).isEqualTo("rojo");
  }

  @Test
  void aBearMarketOrFearTurnsTheLightRed() {
    assertThat(MarketSignals.evaluate(inputs(15.0, -22.0, null, null, null, null, null)).overall()).isEqualTo("rojo");
    assertThat(MarketSignals.evaluate(inputs(35.0, -3.0, null, null, null, null, null)).overall()).isEqualTo("rojo");
  }

  @Test
  void aCorrectionPlusNervesIsYellow() {
    assertThat(MarketSignals.evaluate(inputs(24.0, -12.0, null, null, null, null, null)).overall()).isEqualTo("amarillo");
  }

  @Test
  void anInvertedCurveAloneDoesNotChangeTheLightBecauseItIsASlowSignal() {
    var r = MarketSignals.evaluate(inputs(14.0, -2.0, 5500.0, 5300.0, -0.4, 3.2, 0.0));
    assertThat(state(r, "curve_t10y2y")).isEqualTo("amarillo");
    assertThat(r.overall()).isEqualTo("verde");
  }

  @Test
  void highOrRisingCreditSpreadsRaiseTheAlert() {
    assertThat(state(MarketSignals.evaluate(inputs(null, null, null, null, null, 4.2, 0.0)), "hy_spread")).isEqualTo("amarillo");
    assertThat(state(MarketSignals.evaluate(inputs(null, null, null, null, null, 3.5, 0.6)), "hy_spread")).isEqualTo("amarillo");
    assertThat(state(MarketSignals.evaluate(inputs(null, null, null, null, null, 6.5, 0.0)), "hy_spread")).isEqualTo("rojo");
    assertThat(state(MarketSignals.evaluate(inputs(null, null, null, null, null, 3.5, 1.6)), "hy_spread")).isEqualTo("rojo");
  }

  @Test
  void missingDataNeverBreaksTheLight() {
    var r = MarketSignals.evaluate(inputs(null, null, null, null, null, null, null));
    assertThat(r.overall()).isEqualTo(MarketSignals.NO_DATA);
    assertThat(r.indicators()).hasSize(5).allMatch(i -> i.state().equals(MarketSignals.NO_DATA));
    assertThat(MarketSignals.evaluate(inputs(14.0, null, null, null, null, null, null)).overall()).isEqualTo("verde");
  }

  @Test
  void belowTheTwoHundredDayAverageIsWeakerTheFartherItGoes() {
    assertThat(state(MarketSignals.evaluate(inputs(null, null, 100.0, 95.0, null, null, null)), "sp500_vs_sma200")).isEqualTo("verde");
    assertThat(state(MarketSignals.evaluate(inputs(null, null, 97.0, 100.0, null, null, null)), "sp500_vs_sma200")).isEqualTo("amarillo");
    assertThat(state(MarketSignals.evaluate(inputs(null, null, 90.0, 100.0, null, null, null)), "sp500_vs_sma200")).isEqualTo("rojo");
  }

  // ---- plan para caídas ----

  final List<InvestConfig.DropRule> rules = InvestConfig.defaults().dropRules();

  @Test
  void noRuleAppliesWithoutARelevantDrop() {
    var s = DropPlan.evaluate(-5, rules, 20000, 15000);
    assertThat(s.active()).isNull();
    assertThat(s.next().atPct()).isEqualTo(-10);
  }

  @Test
  void theDeepestReachedRuleIsTheActiveOne() {
    assertThat(DropPlan.evaluate(-12, rules, 20000, 15000).active().atPct()).isEqualTo(-10);
    assertThat(DropPlan.evaluate(-12, rules, 20000, 15000).next().atPct()).isEqualTo(-20);
    assertThat(DropPlan.evaluate(-25, rules, 20000, 15000).active().atPct()).isEqualTo(-20);
    var crisis = DropPlan.evaluate(-35, rules, 20000, 15000);
    assertThat(crisis.active().atPct()).isEqualTo(-30);
    assertThat(crisis.next()).isNull();
    assertThat(crisis.emergencyWarning()).isFalse();
  }

  @Test
  void theExtraContributionRuleNeedsTheEmergencyFund() {
    var s = DropPlan.evaluate(-35, rules, 5000, 15000);
    assertThat(s.emergencyWarning()).isTrue();
    assertThat(s.message()).contains("fondo de emergencia").contains("15000").contains("5000");
  }

  // ---- narrativa ----

  static Narrative.Inputs day(Double sp, Double pf, double bonds, DropPlan.Status drops, boolean rebalance, String light, boolean pos) {
    return new Narrative.Inputs(sp, pf, bonds, drops, rebalance, light, pos);
  }

  @Test
  void theExampleFromTheSpecificationReadsNaturally() {
    var text = Narrative.build(day(-2.1, -1.3, 40, DropPlan.evaluate(-3, rules, 0, 0), false, "verde", true));
    assertThat(text).contains("El S&P 500 cayó 2.1%").contains("Tu portafolio bajó 1.3%").contains("bonos").contains("normal").endsWith("No requiere acción.");
  }

  @Test
  void anActiveDropRuleIsQuotedInTheText() {
    var text = Narrative.build(day(-1.0, -0.8, 40, DropPlan.evaluate(-22, rules, 20000, 15000), false, "rojo", true));
    assertThat(text).contains("Tu plan para caídas dice").contains("Mercado bajista");
  }

  @Test
  void aRebalanceNeedIsMentioned() {
    assertThat(Narrative.build(day(0.5, 0.4, 40, DropPlan.evaluate(-1, rules, 0, 0), true, "verde", true))).contains("banda");
  }

  @Test
  void withoutPositionsTheTextExplainsHowToStart() {
    assertThat(Narrative.build(day(0.5, null, 0, null, false, "verde", false))).startsWith("Aún no registras posiciones");
  }

  @Test
  void aQuietDayIsDescribedAsSuch() {
    assertThat(Narrative.build(day(0.1, 0.05, 40, DropPlan.evaluate(0, rules, 0, 0), false, "verde", true))).contains("casi no se movió");
  }
}
