package com.sglabs.farmacia.invest;

import java.util.*;

/**
 * Decide qué avisos mandar comparando el resumen de hoy con el estado guardado de la última vez.
 * Solo avisa cuando algo cambia, para que el Telegram no se vuelva ruido.
 */
final class AlertRules {
  record Result(List<String> messages, Map<String, String> newState) {}

  private AlertRules() {}

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object o) {
    return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
  }

  static Result evaluate(Map<String, String> prev, Map<String, Object> snapshot) {
    var msgs = new ArrayList<String>();
    var next = new HashMap<String, String>();
    var market = map(snapshot.get("market"));
    var portfolio = map(snapshot.get("portfolio"));
    var plan = map(snapshot.get("plan"));

    String overall = String.valueOf(market.getOrDefault("overall", "sin dato"));
    next.put("semaforo", overall);
    if ("rojo".equals(overall) && !"rojo".equals(prev.get("semaforo")))
      msgs.add("🔴 El semáforo de riesgo pasó a ROJO. " + market.getOrDefault("summary", "") + " Revisa tu plan para caídas; no vendas por pánico.");

    boolean needed = Boolean.TRUE.equals(map(portfolio.get("rebalance")).get("needed"));
    next.put("rebalance", String.valueOf(needed));
    if (needed && !"true".equals(prev.get("rebalance"))) {
      Object buys = map(snapshot.get("calendar")).get("buys");
      var sb = new StringBuilder("⚖️ Tu portafolio se salió de la banda. Con tu próximo aporte compra: ");
      if (!(buys instanceof List<?> list) || list.isEmpty()) sb.append("(sin aporte configurado)");
      else {
        var parts = new ArrayList<String>();
        for (var b : list) parts.add(map(b).get("ticker") + " Q" + map(b).get("gtq"));
        sb.append(String.join(", ", parts));
      }
      sb.append(". No hace falta vender.");
      msgs.add(sb.toString());
    }

    Object dd = plan.get("drawdownPct");
    int level = 0;
    if (dd instanceof Number n) {
      double d = n.doubleValue();
      level = d <= -30 ? 30 : d <= -20 ? 20 : d <= -10 ? 10 : 0;
    }
    int prevLevel = parse(prev.get("dd_level"));
    next.put("dd_level", String.valueOf(level));
    if (level > prevLevel) msgs.add("📉 El S&P 500 cruzó −" + level + "% desde su máximo. " + plan.getOrDefault("message", ""));
    return new Result(msgs, next);
  }

  private static int parse(String s) {
    try {
      return s == null ? 0 : Integer.parseInt(s);
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
