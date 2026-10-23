package com.sglabs.farmacia.invest;

import java.util.*;

/** Cuánto perdería el portafolio si se repitiera una caída histórica de cada clase de activo. */
final class StressTest {
  record Result(String scenario, double lossUsd, double lossPct) {}

  private StressTest() {}

  /**
   * @param classes ticker → clase de activo (equity_us, equity_intl, bonds, cash…)
   * @param scenarios nombre del escenario → clase → variación (por ejemplo -0.55)
   */
  static List<Result> run(Map<String, Double> values, Map<String, String> classes, Map<String, Map<String, Double>> scenarios) {
    double total = values.values().stream().mapToDouble(Double::doubleValue).sum();
    var out = new ArrayList<Result>();
    for (var s : scenarios.entrySet()) {
      double loss = 0;
      for (var v : values.entrySet()) {
        String cls = classes.getOrDefault(v.getKey(), "equity_us");
        loss += v.getValue() * s.getValue().getOrDefault(cls, 0.0);
      }
      out.add(new Result(s.getKey(), loss, total == 0 ? 0 : loss / total * 100));
    }
    return out;
  }
}
