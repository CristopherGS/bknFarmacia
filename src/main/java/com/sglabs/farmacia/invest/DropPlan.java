package com.sglabs.farmacia.invest;

import java.util.*;

/** Aplica TUS reglas para caídas, definidas de antemano, para no decidir por miedo. */
final class DropPlan {
  record Status(InvestConfig.DropRule active, InvestConfig.DropRule next, double drawdownPct, String message, boolean emergencyWarning) {}

  static final String REMINDER = "No vendas en pánico. Tus reglas se escribieron con la cabeza fría para este momento: síguelas.";

  private DropPlan() {}

  static Status evaluate(double spDrawdownPct, List<InvestConfig.DropRule> rules, double fundCurrent, double fundMin) {
    var sorted = new ArrayList<>(rules);
    sorted.sort(Comparator.comparingInt(InvestConfig.DropRule::atPct).reversed()); // -10, -20, -30
    InvestConfig.DropRule active = null, next = null;
    for (var r : sorted) {
      if (spDrawdownPct <= r.atPct()) active = r;
      else if (next == null) next = r;
    }
    if (active == null)
      return new Status(null, next, spDrawdownPct, "Sin caída relevante. Sigue tu aporte mensual y tu plan.", false);
    boolean warn = active.needsEmergencyFund() && fundCurrent < fundMin;
    String message = active.title() + ": " + active.action();
    if (warn) message += " Antes, completa tu fondo de emergencia (mínimo Q" + Math.round(fundMin) + "): hoy tienes Q" + Math.round(fundCurrent) + ".";
    return new Status(active, next, spDrawdownPct, message, warn);
  }
}
