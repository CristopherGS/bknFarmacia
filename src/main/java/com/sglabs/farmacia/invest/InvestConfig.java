package com.sglabs.farmacia.invest;

import java.util.*;

/** Tu plan de inversión: cartera objetivo, aporte, meta y reglas para caídas. Editable desde la web. */
public record InvestConfig(
    Map<String, Double> target,
    Map<String, String> classes,
    double rebalanceBandPts,
    double concentrationLimitPct,
    double monthlyContributionGtq,
    double goalGtq,
    double emergencyFundMinGtq,
    double emergencyFundCurrentGtq,
    double fxFallbackGtqPerUsd,
    double expectedAnnualReturnPct,
    List<DropRule> dropRules,
    Map<String, Map<String, Double>> stress) {

  public record DropRule(int atPct, String title, String action, boolean needsEmergencyFund) {}

  /** Mapa que conserva el orden de inserción, para que la pantalla siempre muestre VOO, VXUS, BND, SGOV. */
  @SuppressWarnings("unchecked")
  private static <V> Map<String, V> ordered(Object... kv) {
    var m = new LinkedHashMap<String, V>();
    for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (V) kv[i + 1]);
    return m;
  }

  public static InvestConfig defaults() {
    var stress = new LinkedHashMap<String, Map<String, Double>>();
    // Caídas aproximadas de máximo a mínimo por clase de activo. Son orientativas: ajústalas a tu criterio.
    stress.put("2008", Map.of("equity_us", -0.55, "equity_intl", -0.60, "bonds", 0.05, "cash", 0.0));
    stress.put("2020", Map.of("equity_us", -0.34, "equity_intl", -0.34, "bonds", -0.03, "cash", 0.0));
    stress.put("2022", Map.of("equity_us", -0.25, "equity_intl", -0.30, "bonds", -0.17, "cash", 0.0));
    return new InvestConfig(
        ordered("VOO", 50.0, "VXUS", 10.0, "BND", 30.0, "SGOV", 10.0),
        ordered("VOO", "equity_us", "VXUS", "equity_intl", "BND", "bonds", "SGOV", "cash"),
        5,
        55,
        2000,
        100000,
        15000,
        0,
        7.70,
        6,
        List.of(
            new DropRule(-10, "Corrección normal", "Sigue tu aporte mensual. Las correcciones de 10% ocurren casi todos los años.", false),
            new DropRule(-20, "Mercado bajista", "Rebalancea: el aporte de este mes va a las acciones, que están baratas frente a tu plan.", false),
            new DropRule(-30, "Oportunidad histórica", "Si tu fondo de emergencia está completo, considera un aporte extra. No vendas.", true)),
        stress);
  }

  /** Lanza IllegalArgumentException con un mensaje claro si algo no tiene sentido. */
  public void validate() {
    if (target == null || target.isEmpty()) throw new IllegalArgumentException("Define al menos un ETF en la cartera objetivo.");
    double sum = 0;
    for (var e : target.entrySet()) {
      if (!e.getKey().matches("[A-Z0-9.^-]{1,15}"))
        throw new IllegalArgumentException("Ticker no válido: " + e.getKey() + " (usa mayúsculas, por ejemplo VOO).");
      if (e.getValue() == null || e.getValue() < 0 || e.getValue() > 100)
        throw new IllegalArgumentException("El porcentaje de " + e.getKey() + " debe estar entre 0 y 100.");
      sum += e.getValue();
    }
    if (Math.abs(sum - 100) > 0.01)
      throw new IllegalArgumentException("La cartera objetivo debe sumar 100%. Hoy suma " + Math.round(sum * 100) / 100.0 + "%.");
    if (rebalanceBandPts < 0 || rebalanceBandPts > 50) throw new IllegalArgumentException("La banda de rebalanceo debe estar entre 0 y 50 puntos.");
    if (concentrationLimitPct <= 0 || concentrationLimitPct > 100) throw new IllegalArgumentException("El límite de concentración debe estar entre 1 y 100%.");
    for (double v : new double[] {monthlyContributionGtq, goalGtq, emergencyFundMinGtq, emergencyFundCurrentGtq})
      if (v < 0) throw new IllegalArgumentException("Los montos no pueden ser negativos.");
    if (fxFallbackGtqPerUsd <= 0) throw new IllegalArgumentException("El tipo de cambio de respaldo debe ser mayor que 0.");
    if (expectedAnnualReturnPct < -50 || expectedAnnualReturnPct > 30)
      throw new IllegalArgumentException("El rendimiento anual esperado debe estar entre -50% y 30%.");
    if (dropRules == null) throw new IllegalArgumentException("Faltan las reglas para caídas.");
    for (var r : dropRules)
      if (r.atPct() >= 0 || r.atPct() < -90) throw new IllegalArgumentException("Cada regla de caída debe estar entre -1% y -90%.");
    if (stress == null) throw new IllegalArgumentException("Faltan los escenarios de estrés.");
  }

  /** Clase de activo de un ticker: la configurada, o renta variable de EE. UU. si no se indicó. */
  public String classOf(String ticker) {
    return classes == null ? "equity_us" : classes.getOrDefault(ticker, "equity_us");
  }
}
