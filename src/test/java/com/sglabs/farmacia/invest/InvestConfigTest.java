package com.sglabs.farmacia.invest;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;

class InvestConfigTest {
  final ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  @Test
  void theDefaultsAreValidAndMatchThePlan() {
    var c = InvestConfig.defaults();
    c.validate();
    assertThat(c.target()).containsEntry("VOO", 50.0).containsEntry("VXUS", 10.0).containsEntry("BND", 30.0).containsEntry("SGOV", 10.0);
    assertThat(c.rebalanceBandPts()).isEqualTo(5);
    assertThat(c.goalGtq()).isEqualTo(100000);
    assertThat(c.dropRules()).extracting(InvestConfig.DropRule::atPct).containsExactly(-10, -20, -30);
    assertThat(c.stress()).containsKeys("2008", "2020", "2022");
  }

  @Test
  void itSurvivesAJsonRoundTrip() throws Exception {
    var c = InvestConfig.defaults();
    var back = json.readValue(json.writeValueAsString(c), InvestConfig.class);
    assertThat(back).isEqualTo(c);
    back.validate();
  }

  @Test
  void targetsMustAddUpToOneHundred() {
    var c = InvestConfig.defaults();
    c.target().put("VOO", 40.0);
    assertThatThrownBy(c::validate).hasMessageContaining("100%").hasMessageContaining("90");
  }

  @Test
  void badTickersAndAmountsAreRejectedWithAClearMessage() {
    var lower = InvestConfig.defaults();
    lower.target().put("voo", 0.0);
    assertThatThrownBy(lower::validate).hasMessageContaining("Ticker no válido");
    var neg = new InvestConfig(InvestConfig.defaults().target(), Map.of(), 5, 55, -1, 100000, 0, 0, 7.7, 6, List.of(), Map.of());
    assertThatThrownBy(neg::validate).hasMessageContaining("negativos");
    var badRule = new InvestConfig(InvestConfig.defaults().target(), Map.of(), 5, 55, 100, 1000, 0, 0, 7.7, 6, List.of(new InvestConfig.DropRule(10, "x", "y", false)), Map.of());
    assertThatThrownBy(badRule::validate).hasMessageContaining("regla de caída");
  }

  @Test
  void unknownTickersDefaultToUsEquities() {
    assertThat(InvestConfig.defaults().classOf("BND")).isEqualTo("bonds");
    assertThat(InvestConfig.defaults().classOf("QQQ")).isEqualTo("equity_us");
  }
}
