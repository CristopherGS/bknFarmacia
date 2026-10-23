package com.sglabs.farmacia.invest;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class RebalancerTest {
  static final Map<String, Double> TARGET = new LinkedHashMap<>(Map.of("VOO", 50.0, "VXUS", 10.0, "BND", 30.0, "SGOV", 10.0));

  static Map<String, Double> values(double voo, double vxus, double bnd, double sgov) {
    return new LinkedHashMap<>(Map.of("VOO", voo, "VXUS", vxus, "BND", bnd, "SGOV", sgov));
  }

  @Test
  void flagsTheEtfsOutsideTheBand() {
    var plan = Rebalancer.plan(values(600, 100, 200, 100), TARGET, 5, 0);
    assertThat(plan.needed()).isTrue();
    var voo = plan.rows().stream().filter(r -> r.ticker().equals("VOO")).findFirst().orElseThrow();
    assertThat(voo.weightPct()).isCloseTo(60, within(1e-9));
    assertThat(voo.deviationPts()).isCloseTo(10, within(1e-9));
    assertThat(voo.outOfBand()).isTrue();
    assertThat(plan.rows().stream().filter(Rebalancer.Row::outOfBand).map(Rebalancer.Row::ticker)).containsExactlyInAnyOrder("VOO", "BND");
  }

  @Test
  void theContributionGoesOnlyToWhatIsBelowTargetAndNeverSells() {
    var plan = Rebalancer.plan(values(600, 100, 200, 100), TARGET, 5, 100);
    assertThat(plan.buys()).doesNotContainKey("VOO"); // ya está por encima: no se compra
    assertThat(plan.buys().get("BND")).isCloseTo(86.6667, within(0.001));
    assertThat(plan.buys().get("VXUS")).isCloseTo(6.6667, within(0.001));
    assertThat(plan.buys().get("SGOV")).isCloseTo(6.6667, within(0.001));
    assertThat(plan.buys().values().stream().mapToDouble(Double::doubleValue).sum()).isCloseTo(100, within(0.01));
    assertThat(plan.buys().values()).allMatch(v -> v > 0);
    assertThat(plan.afterMaxDeviationPts()).isCloseTo(4.545, within(0.01)); // mejora frente a los 10 puntos de antes
  }

  @Test
  void whenEverythingIsOnTargetTheContributionFollowsTheTargetWeights() {
    var plan = Rebalancer.plan(values(500, 100, 300, 100), TARGET, 5, 100);
    assertThat(plan.needed()).isFalse();
    assertThat(plan.buys().get("VOO")).isCloseTo(50, within(0.001));
    assertThat(plan.buys().get("BND")).isCloseTo(30, within(0.001));
  }

  @Test
  void noContributionMeansNoPurchases() {
    assertThat(Rebalancer.plan(values(600, 100, 200, 100), TARGET, 5, 0).buys()).isEmpty();
  }

  @Test
  void anEmptyPortfolioStartsFromTheTargets() {
    var plan = Rebalancer.plan(new LinkedHashMap<>(), TARGET, 5, 1000);
    assertThat(plan.buys().get("VOO")).isCloseTo(500, within(0.001));
    assertThat(plan.buys().get("SGOV")).isCloseTo(100, within(0.001));
  }

  @Test
  void aTickerHeldButNotInTheTargetIsTreatedAsZeroTarget() {
    var v = values(500, 100, 300, 100);
    v.put("XYZ", 100.0);
    var plan = Rebalancer.plan(v, TARGET, 5, 100);
    assertThat(plan.rows().stream().filter(r -> r.ticker().equals("XYZ")).findFirst().orElseThrow().targetPct()).isZero();
    assertThat(plan.buys()).doesNotContainKey("XYZ");
  }
}
