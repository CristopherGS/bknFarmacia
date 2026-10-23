package com.sglabs.farmacia.invest;

import java.util.*;

/**
 * Rebalanceo solo con compras: nunca propone vender. Reparte el aporte del mes entre los activos que
 * están por debajo de su objetivo, de modo que el portafolio se acerque al objetivo sin generar
 * impuestos ni costos de venta.
 */
final class Rebalancer {
  record Row(String ticker, double value, double weightPct, double targetPct, double deviationPts, boolean outOfBand) {}

  record Plan(List<Row> rows, Map<String, Double> buys, boolean needed, double afterMaxDeviationPts) {}

  private Rebalancer() {}

  static List<Row> rows(Map<String, Double> values, Map<String, Double> targetPct, double bandPts) {
    double total = values.values().stream().mapToDouble(Double::doubleValue).sum();
    var tickers = new LinkedHashSet<>(targetPct.keySet());
    tickers.addAll(values.keySet());
    var rows = new ArrayList<Row>();
    for (String t : tickers) {
      double v = values.getOrDefault(t, 0.0);
      double w = total == 0 ? 0 : v / total * 100;
      double target = targetPct.getOrDefault(t, 0.0);
      rows.add(new Row(t, v, w, target, w - target, Math.abs(w - target) > bandPts));
    }
    return rows;
  }

  /** @param contribution aporte a repartir, en la misma moneda que `values`. */
  static Plan plan(Map<String, Double> values, Map<String, Double> targetPct, double bandPts, double contribution) {
    var rows = rows(values, targetPct, bandPts);
    boolean needed = rows.stream().anyMatch(Row::outOfBand);
    double total = values.values().stream().mapToDouble(Double::doubleValue).sum();
    double after = total + Math.max(0, contribution);
    var need = new LinkedHashMap<String, Double>();
    double needSum = 0;
    for (var r : rows) {
      double gap = Math.max(0, r.targetPct() / 100 * after - r.value());
      need.put(r.ticker(), gap);
      needSum += gap;
    }
    var buys = new LinkedHashMap<String, Double>();
    if (contribution > 0) {
      for (var e : need.entrySet()) {
        double share = needSum > 0 ? e.getValue() / needSum : 0;
        buys.put(e.getKey(), contribution * share);
      }
      // Si no hay déficit en ningún activo (todo en objetivo), el aporte sigue los pesos objetivo.
      if (needSum == 0) {
        double sumTargets = targetPct.values().stream().mapToDouble(Double::doubleValue).sum();
        for (var e : targetPct.entrySet()) buys.put(e.getKey(), sumTargets == 0 ? 0 : contribution * e.getValue() / sumTargets);
      }
    }
    double worst = 0;
    for (var r : rows) {
      double v = r.value() + buys.getOrDefault(r.ticker(), 0.0);
      double w = after == 0 ? 0 : v / after * 100;
      worst = Math.max(worst, Math.abs(w - r.targetPct()));
    }
    buys.values().removeIf(v -> v < 0.005);
    return new Plan(rows, buys, needed, worst);
  }
}
