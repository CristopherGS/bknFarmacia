package com.sglabs.farmacia.invest;

import java.util.*;

/** Semáforo de riesgo del mercado: cada indicador con su estado, qué significa y qué ha pasado antes. */
final class MarketSignals {
  record Indicator(String id, String label, String value, String state, String reading, String means, String history) {}

  record Result(String overall, List<Indicator> indicators, String summary) {}

  /** Los valores nulos significan "sin dato": el indicador se muestra gris y no cuenta en el semáforo. */
  record Inputs(
      Double vix,
      Double spDrawdownPct,
      Double spPrice,
      Double spSma200,
      Double curve10y2y,
      Double hySpread,
      Double hyChange20d) {}

  static final String NO_DATA = "sin dato";

  private MarketSignals() {}

  private static String f(double v, int d) {
    return String.format(Locale.US, "%." + d + "f", v);
  }

  static Result evaluate(Inputs in) {
    var list = new ArrayList<Indicator>();

    if (in.vix() == null) {
      list.add(new Indicator("vix", "VIX (índice del miedo)", "—", NO_DATA, "Sin dato por ahora.", VIX_MEANS, VIX_HISTORY));
    } else {
      double v = in.vix();
      String state = v < 20 ? "verde" : v <= 30 ? "amarillo" : "rojo";
      String reading = v < 20 ? "Tranquilo: el mercado espera movimientos normales." : v <= 30 ? "Nervioso: el mercado espera movimientos más grandes de lo usual." : "Miedo: el mercado espera movimientos muy bruscos.";
      list.add(new Indicator("vix", "VIX (índice del miedo)", f(v, 1), state, reading, VIX_MEANS, VIX_HISTORY));
    }

    if (in.spDrawdownPct() == null) {
      list.add(new Indicator("sp500_drawdown", "S&P 500 frente a su máximo", "—", NO_DATA, "Sin dato por ahora.", DD_MEANS, DD_HISTORY));
    } else {
      double d = in.spDrawdownPct();
      String state = d > -10 ? "verde" : d > -20 ? "amarillo" : "rojo";
      String reading =
          d > -10 ? "Cerca de su máximo: sin caída relevante."
              : d > -20 ? "Corrección (caída de 10% o más): es normal y suele durar meses."
              : d > -30 ? "Mercado bajista (caída de 20% o más): incómodo, pero ha pasado antes y se recuperó."
              : "Crisis (caída de 30% o más): las peores épocas fueron también las mejores para comprar a largo plazo.";
      list.add(new Indicator("sp500_drawdown", "S&P 500 frente a su máximo", f(d, 1) + "%", state, reading, DD_MEANS, DD_HISTORY));
    }

    if (in.spPrice() == null || in.spSma200() == null) {
      list.add(new Indicator("sp500_vs_sma200", "S&P 500 frente a su media de 200 días", "—", NO_DATA, "Sin dato por ahora.", SMA_MEANS, SMA_HISTORY));
    } else {
      double gap = (in.spPrice() / in.spSma200() - 1) * 100;
      String state = gap >= 0 ? "verde" : gap > -8 ? "amarillo" : "rojo";
      String reading = gap >= 0 ? "Por encima de su media de 200 días: tendencia sana." : gap > -8 ? "Por debajo de su media de 200 días: la tendencia se debilitó." : "Muy por debajo de su media de 200 días: tendencia claramente bajista.";
      list.add(new Indicator("sp500_vs_sma200", "S&P 500 frente a su media de 200 días", (gap >= 0 ? "+" : "") + f(gap, 1) + "%", state, reading, SMA_MEANS, SMA_HISTORY));
    }

    if (in.curve10y2y() == null) {
      list.add(new Indicator("curve_t10y2y", "Curva de rendimientos (10 años − 2 años)", "—", NO_DATA, "Sin dato por ahora.", CURVE_MEANS, CURVE_HISTORY));
    } else {
      double c = in.curve10y2y();
      // Es una señal lenta: nunca pasa de amarillo para no empujar a decidir por miedo.
      String state = c >= 0 ? "verde" : "amarillo";
      String reading = c >= 0 ? "Normal: los bonos largos pagan más que los cortos." : "Invertida: los bonos cortos pagan más que los largos. Es una señal lenta, no una alarma inmediata.";
      list.add(new Indicator("curve_t10y2y", "Curva de rendimientos (10 años − 2 años)", (c >= 0 ? "+" : "") + f(c, 2) + " pts", state, reading, CURVE_MEANS, CURVE_HISTORY));
    }

    if (in.hySpread() == null) {
      list.add(new Indicator("hy_spread", "Spread de bonos de alto riesgo", "—", NO_DATA, "Sin dato por ahora.", HY_MEANS, HY_HISTORY));
    } else {
      double s = in.hySpread();
      double ch = in.hyChange20d() == null ? 0 : in.hyChange20d();
      String state = s > 6 || ch >= 1.5 ? "rojo" : s >= 4 || ch >= 0.5 ? "amarillo" : "verde";
      String trend = ch >= 0.5 ? "subiendo" : ch <= -0.5 ? "bajando" : "estable";
      String reading = state.equals("verde") ? "Bajo y estable: las empresas se financian sin tensión." : state.equals("amarillo") ? "Elevado o subiendo (" + trend + "): empieza la cautela en el crédito." : "Alto o subiendo con fuerza (" + trend + "): el crédito se está tensando.";
      list.add(new Indicator("hy_spread", "Spread de bonos de alto riesgo", f(s, 2) + " pts", state, reading, HY_MEANS, HY_HISTORY));
    }

    int score = 0, reds = 0, known = 0;
    for (var i : list) {
      switch (i.state()) {
        case "amarillo" -> { score += 1; known++; }
        case "rojo" -> { score += 2; reds++; known++; }
        case "verde" -> known++;
        default -> {}
      }
    }
    boolean vixRed = in.vix() != null && in.vix() > 30;
    boolean bear = in.spDrawdownPct() != null && in.spDrawdownPct() <= -20;
    String overall;
    if (known == 0) overall = NO_DATA;
    else if (vixRed || bear || (reds >= 1 && score >= 3)) overall = "rojo";
    else if (score >= 2 || reds >= 1) overall = "amarillo";
    else overall = "verde";

    String summary =
        switch (overall) {
          case "verde" -> "Mercado tranquilo. Sigue tu plan y tu aporte mensual.";
          case "amarillo" -> "Hay señales de cautela. No es momento de cambiar tu plan: es momento de no improvisar.";
          case "rojo" -> "Mercado bajo estrés. Revisa tu plan para caídas antes de hacer nada, y no vendas por miedo.";
          default -> "Todavía no hay datos de mercado.";
        };
    return new Result(overall, list, summary);
  }

  private static final String VIX_MEANS = "El VIX mide cuánto espera el mercado que se muevan las acciones del S&P 500 el próximo mes. Más alto significa más nervios.";
  private static final String VIX_HISTORY = "Su promedio de largo plazo ronda 19. Superó 80 en 2008 y 2020, y rara vez se queda arriba de 30 más de unos meses: el miedo extremo suele pasar.";
  private static final String DD_MEANS = "Cuánto ha caído el S&P 500 desde su punto más alto. Es la medida más directa de qué tan mal está la bolsa.";
  private static final String DD_HISTORY = "Las correcciones de 10% ocurren casi cada año; las caídas de 20% o más, una vez cada varios años. Todas se recuperaron, aunque algunas tardaron años.";
  private static final String SMA_MEANS = "La media de 200 días es el precio promedio del último año de operación. Estar por encima suele indicar una tendencia sana.";
  private static final String SMA_HISTORY = "Cruzar por debajo ha dado muchas falsas alarmas: sirve para entender el ánimo del mercado, no para vender.";
  private static final String CURVE_MEANS = "Compara lo que pagan los bonos del Tesoro a 10 años contra los de 2 años. Normalmente los largos pagan más.";
  private static final String CURVE_HISTORY = "Se invirtió antes de casi todas las recesiones de EE. UU., pero con meses o años de adelanto, y también en periodos sin recesión inmediata.";
  private static final String HY_MEANS = "Es el rendimiento extra que piden los inversionistas por prestar a empresas con más riesgo de impago. Sube cuando crece el miedo al crédito.";
  private static final String HY_HISTORY = "En épocas tranquilas ronda 3 a 4 puntos; superó 6 en tensiones fuertes y llegó a casi 20 en la crisis de 2008.";
}
