package com.sglabs.farmacia.invest;

/** Métricas de rendimiento y riesgo sobre series de precios o de valores. Funciones puras. */
final class Metrics {
  static final int TRADING_DAYS = 252;

  private Metrics() {}

  /** Rendimientos simples entre puntos consecutivos: v[i]/v[i-1] - 1. */
  static double[] returns(double[] v) {
    if (v.length < 2) return new double[0];
    var r = new double[v.length - 1];
    for (int i = 1; i < v.length; i++) r[i - 1] = v[i - 1] == 0 ? 0 : v[i] / v[i - 1] - 1;
    return r;
  }

  /** Índice base 100 a partir de rendimientos diarios (rendimiento ponderado por tiempo). */
  static double[] index(double[] daily) {
    var out = new double[daily.length + 1];
    out[0] = 100;
    for (int i = 0; i < daily.length; i++) out[i + 1] = out[i] * (1 + daily[i]);
    return out;
  }

  static double mean(double[] x) {
    if (x.length == 0) return 0;
    double s = 0;
    for (double v : x) s += v;
    return s / x.length;
  }

  /** Desviación estándar muestral. */
  static double stdev(double[] x) {
    if (x.length < 2) return 0;
    double m = mean(x), s = 0;
    for (double v : x) s += (v - m) * (v - m);
    return Math.sqrt(s / (x.length - 1));
  }

  static double annualizedVol(double[] daily) {
    return stdev(daily) * Math.sqrt(TRADING_DAYS);
  }

  /** Caída de cada punto respecto al máximo anterior (0 o negativo). */
  static double[] drawdowns(double[] index) {
    var out = new double[index.length];
    double peak = Double.NEGATIVE_INFINITY;
    for (int i = 0; i < index.length; i++) {
      peak = Math.max(peak, index[i]);
      out[i] = peak <= 0 ? 0 : index[i] / peak - 1;
    }
    return out;
  }

  static double maxDrawdown(double[] index) {
    double worst = 0;
    for (double d : drawdowns(index)) worst = Math.min(worst, d);
    return worst;
  }

  /** Sharpe anualizado: (rendimiento medio anual - tasa libre de riesgo) / volatilidad anual. */
  static Double sharpe(double[] daily, double riskFreeAnnual) {
    double vol = annualizedVol(daily);
    if (daily.length < 2 || vol == 0) return null;
    return (mean(daily) * TRADING_DAYS - riskFreeAnnual) / vol;
  }

  /** Beta = cov(portafolio, mercado) / var(mercado). Las series deben alinearse por fecha. */
  static Double beta(double[] portfolio, double[] market) {
    if (portfolio.length != market.length || portfolio.length < 2) return null;
    double mp = mean(portfolio), mm = mean(market), cov = 0, var = 0;
    for (int i = 0; i < portfolio.length; i++) {
      cov += (portfolio[i] - mp) * (market[i] - mm);
      var += (market[i] - mm) * (market[i] - mm);
    }
    return var == 0 ? null : cov / var;
  }

  /** Media móvil simple de los últimos n valores, o null si no hay suficientes. */
  static Double sma(double[] v, int n) {
    if (v.length < n || n <= 0) return null;
    double s = 0;
    for (int i = v.length - n; i < v.length; i++) s += v[i];
    return s / n;
  }

  /** Rendimiento anual compuesto entre dos valores separados por `days` días naturales. */
  static Double cagr(double start, double end, long days) {
    if (start <= 0 || end <= 0 || days < 30) return null;
    return Math.pow(end / start, 365.0 / days) - 1;
  }
}
