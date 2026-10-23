package com.sglabs.farmacia.invest;

import java.util.*;

/** El párrafo "Lo que importa hoy": reglas simples, sin IA de pago. */
final class Narrative {
  record Inputs(
      Double spDayPct,
      Double portfolioDayPct,
      double bondsAndCashWeightPct,
      DropPlan.Status drops,
      boolean rebalanceNeeded,
      String semaforo,
      boolean hasPositions) {}

  private Narrative() {}

  private static String pct(double v) {
    return String.format(Locale.US, "%.1f%%", Math.abs(v));
  }

  static String build(Inputs in) {
    if (!in.hasPositions())
      return "Aún no registras posiciones. Agrégalas en la sección de configuración para ver cómo va tu portafolio. Mientras tanto, el semáforo del mercado ya funciona.";
    var parts = new ArrayList<String>();
    Double sp = in.spDayPct();
    Double pf = in.portfolioDayPct();

    if (sp != null) {
      if (Math.abs(sp) < 0.3) parts.add("El S&P 500 casi no se movió hoy (" + (sp >= 0 ? "+" : "-") + pct(sp) + ").");
      else parts.add("El S&P 500 " + (sp > 0 ? "subió " : "cayó ") + pct(sp) + ".");
    }
    if (pf != null) {
      String move = Math.abs(pf) < 0.1 ? "casi no cambió" : (pf > 0 ? "subió " : "bajó ") + pct(pf);
      String why = "";
      if (sp != null && sp < -1 && pf < 0 && Math.abs(pf) < Math.abs(sp) * 0.8 && in.bondsAndCashWeightPct() > 5)
        why = " porque los bonos y el efectivo amortiguaron la caída";
      else if (sp != null && sp > 1 && pf > 0 && pf < sp * 0.8 && in.bondsAndCashWeightPct() > 5)
        why = " menos que el S&P 500 porque parte de tu dinero está en bonos y efectivo, que suben menos y también caen menos";
      parts.add("Tu portafolio " + move + why + ".");
    }
    if (sp != null) {
      if (sp <= -2) parts.add("Esto es normal: caídas diarias de más de 2% pasan varias veces al año.");
      else if (sp <= -1) parts.add("Es un movimiento normal de mercado.");
    }

    var d = in.drops();
    if (d != null && d.active() != null) parts.add("Tu plan para caídas dice: " + d.message());
    else if (in.rebalanceNeeded()) parts.add("Algún ETF se salió de su banda: revisa el calendario de compras para ver qué comprar con tu próximo aporte.");
    else if ("rojo".equals(in.semaforo())) parts.add("El mercado está bajo estrés, pero tus reglas no piden ninguna acción todavía. No improvises.");
    else parts.add("No requiere acción.");
    return String.join(" ", parts);
  }
}
