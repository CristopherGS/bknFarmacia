package com.sglabs.farmacia.invest;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Episodios de caída de una serie de precios: del máximo al mínimo y hasta recuperar ese máximo.
 * Sirve para responder con datos reales "¿cuánto tardó el mercado en recuperarse?".
 */
final class DrawdownEpisodes {
  record Episode(LocalDate peak, LocalDate trough, LocalDate recovered, double depth) {}

  record Stat(int thresholdPct, int count, Double medianMonthsToBottom, Double medianMonthsToRecover, double worstPct, int stillOpen) {}

  private static final double DAYS_PER_MONTH = 30.44;

  private DrawdownEpisodes() {}

  static List<Episode> episodes(LocalDate[] days, double[] prices, double minDepth) {
    var out = new ArrayList<Episode>();
    if (days.length != prices.length || prices.length == 0) return out;
    int peakIdx = 0, troughIdx = 0;
    for (int i = 1; i < prices.length; i++) {
      if (prices[i] >= prices[peakIdx]) {
        double depth = prices[troughIdx] / prices[peakIdx] - 1;
        if (depth <= -minDepth && troughIdx > peakIdx) out.add(new Episode(days[peakIdx], days[troughIdx], days[i], depth));
        peakIdx = i;
        troughIdx = i;
      } else if (prices[i] < prices[troughIdx]) {
        troughIdx = i;
      }
    }
    double depth = prices[troughIdx] / prices[peakIdx] - 1;
    if (depth <= -minDepth && troughIdx > peakIdx) out.add(new Episode(days[peakIdx], days[troughIdx], null, depth));
    return out;
  }

  private static Double median(List<Double> v) {
    if (v.isEmpty()) return null;
    var s = new ArrayList<>(v);
    Collections.sort(s);
    int n = s.size();
    return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
  }

  /** Estadísticas por umbral (por ejemplo 10, 20 y 30 por ciento). */
  static List<Stat> stats(List<Episode> episodes, int... thresholdsPct) {
    var out = new ArrayList<Stat>();
    for (int t : thresholdsPct) {
      var hit = episodes.stream().filter(e -> e.depth() <= -t / 100.0).toList();
      var toBottom = new ArrayList<Double>();
      var toRecover = new ArrayList<Double>();
      double worst = 0;
      int open = 0;
      for (var e : hit) {
        toBottom.add(ChronoUnit.DAYS.between(e.peak(), e.trough()) / DAYS_PER_MONTH);
        if (e.recovered() == null) open++;
        else toRecover.add(ChronoUnit.DAYS.between(e.peak(), e.recovered()) / DAYS_PER_MONTH);
        worst = Math.min(worst, e.depth() * 100);
      }
      out.add(new Stat(t, hit.size(), median(toBottom), median(toRecover), worst, open));
    }
    return out;
  }
}
