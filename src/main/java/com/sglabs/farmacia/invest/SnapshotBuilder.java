package com.sglabs.farmacia.invest;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Arma el resumen del día que lee la web: portafolio, riesgo, mercado, plan para caídas, calendario de
 * compras y gráficas. Es una función pura de sus entradas, así que se prueba con datos inventados.
 */
final class SnapshotBuilder {
  record Inputs(
      InvestConfig cfg,
      List<Types.Position> positions,
      List<Types.Contribution> contributions,
      Map<String, List<Types.Bar>> bars,
      Map<String, List<Types.Obs>> macro,
      Types.Fx fx,
      List<NewsFilter.Item> news,
      List<DrawdownEpisodes.Stat> recovery,
      Map<String, Object> sources,
      LocalDate today) {}

  static final String SP = "^GSPC";
  /** Con menos días de historial no se calculan volatilidad, Sharpe ni beta: saldrían engañosos. */
  static final int MIN_RETURNS = 30;

  private SnapshotBuilder() {}

  private static double r2(double v) {
    return Math.round(v * 100) / 100.0;
  }

  private static Double r2(Double v) {
    return v == null ? null : r2(v.doubleValue());
  }

  private static Double lastValue(List<Types.Obs> o) {
    return o == null || o.isEmpty() ? null : o.getLast().value();
  }

  static Map<String, Object> build(Inputs in) {
    var cfg = in.cfg();
    var out = new LinkedHashMap<String, Object>();
    var spBars = in.bars().getOrDefault(SP, List.of());
    LocalDate asOf = spBars.isEmpty() ? in.today() : spBars.getLast().day();
    out.put("generatedAt", java.time.Instant.now().toString());
    out.put("asOf", asOf.toString());
    out.put("fx", Map.of("gtqPerUsd", in.fx().gtqPerUsd(), "asOf", in.fx().asOf().toString(), "source", in.fx().source(), "stale", in.fx().stale()));
    double fx = in.fx().gtqPerUsd();

    // ---- precios por día ----
    var priceByTicker = new HashMap<String, TreeMap<LocalDate, Double>>();
    for (var e : in.bars().entrySet()) {
      var m = new TreeMap<LocalDate, Double>();
      e.getValue().forEach(b -> m.put(b.day(), b.close()));
      priceByTicker.put(e.getKey(), m);
    }
    var held = new LinkedHashMap<String, double[]>(); // ticker -> [acciones, costo]
    for (var p : in.positions()) {
      var a = held.computeIfAbsent(p.ticker(), k -> new double[2]);
      a[0] += p.shares();
      a[1] += p.shares() * p.avgPriceUsd();
    }
    boolean hasPositions = !held.isEmpty();

    // ---- valor actual y pesos ----
    var values = new LinkedHashMap<String, Double>();
    var latestPrice = new HashMap<String, Double>();
    for (var e : held.entrySet()) {
      var prices = priceByTicker.get(e.getKey());
      double price = prices == null || prices.isEmpty() ? 0 : prices.lastEntry().getValue();
      latestPrice.put(e.getKey(), price);
      values.put(e.getKey(), e.getValue()[0] * price);
    }
    double totalUsd = values.values().stream().mapToDouble(Double::doubleValue).sum();
    double costUsd = held.values().stream().mapToDouble(a -> a[1]).sum();

    // ---- serie del portafolio (rendimiento ponderado por tiempo, sin contar los aportes) ----
    var series = portfolioSeries(in, held.keySet(), priceByTicker, spBars);
    Double dayChangePct = series.returns.length == 0 ? null : series.returns[series.returns.length - 1] * 100;
    double dayChangeUsd = dayChangePct == null ? 0 : totalUsd - totalUsd / (1 + dayChangePct / 100);

    // ---- métricas de riesgo ----
    var risk = new LinkedHashMap<String, Object>();
    risk.put("historyDays", series.returns.length);
    risk.put("enoughData", series.returns.length >= MIN_RETURNS);
    if (series.returns.length >= MIN_RETURNS) {
      Double fedFunds = lastValue(in.macro().get("FEDFUNDS"));
      double rf = (fedFunds == null ? 0.0 : fedFunds) / 100;
      risk.put("riskFreeAssumed", fedFunds == null);
      risk.put("volAnnPct", r2(Metrics.annualizedVol(series.returns) * 100));
      risk.put("maxDrawdownPct", r2(Metrics.maxDrawdown(series.index) * 100));
      risk.put("sharpe", r2(Metrics.sharpe(series.returns, rf)));
      risk.put("beta", r2(Metrics.beta(series.returns, series.spReturns)));
      risk.put("riskFreePct", r2(rf * 100));
    } else {
      risk.put("note", "Aún hay pocos días de historial (" + series.returns.length + "). Estas métricas aparecen con 30 días o más.");
    }

    // ---- rebalanceo y compras de este mes ----
    double monthlyUsd = fx > 0 ? cfg.monthlyContributionGtq() / fx : 0;
    var plan = Rebalancer.plan(values, cfg.target(), cfg.rebalanceBandPts(), monthlyUsd);
    var holdings = new ArrayList<Map<String, Object>>();
    for (var r : plan.rows()) {
      var a = held.get(r.ticker());
      var prices = priceByTicker.get(r.ticker());
      var h = new LinkedHashMap<String, Object>();
      h.put("ticker", r.ticker());
      h.put("class", cfg.classOf(r.ticker()));
      h.put("shares", a == null ? 0 : a[0]);
      h.put("avgPriceUsd", a == null || a[0] == 0 ? null : r2(a[1] / a[0]));
      h.put("priceUsd", prices == null || prices.isEmpty() ? null : r2(prices.lastEntry().getValue()));
      h.put("priceAsOf", prices == null || prices.isEmpty() ? null : prices.lastKey().toString());
      h.put("valueUsd", r2(r.value()));
      h.put("valueGtq", r2(r.value() * fx));
      h.put("weightPct", r2(r.weightPct()));
      h.put("targetPct", r.targetPct());
      h.put("deviationPts", r2(r.deviationPts()));
      h.put("outOfBand", r.outOfBand());
      h.put("gainPct", a == null || a[1] == 0 ? null : r2((r.value() / a[1] - 1) * 100));
      if (prices != null && !prices.isEmpty()) {
        double max = prices.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        h.put("drawdownPct", max == 0 ? null : r2((prices.lastEntry().getValue() / max - 1) * 100));
        var recent = new ArrayList<List<Object>>();
        var tail = prices.tailMap(prices.lastKey().minusDays(365), true);
        tail.forEach((d, p) -> recent.add(List.of(d.toString(), r2(p))));
        h.put("history", recent);
      }
      holdings.add(h);
    }
    var buys = new ArrayList<Map<String, Object>>();
    for (var b : plan.buys().entrySet()) {
      double price = latestPrice.getOrDefault(b.getKey(), Optional.ofNullable(priceByTicker.get(b.getKey())).filter(m -> !m.isEmpty()).map(m -> m.lastEntry().getValue()).orElse(0.0));
      var row = new LinkedHashMap<String, Object>();
      row.put("ticker", b.getKey());
      row.put("usd", r2(b.getValue()));
      row.put("gtq", r2(b.getValue() * fx));
      row.put("approxShares", price > 0 ? Math.round(b.getValue() / price * 100) / 100.0 : null);
      buys.add(row);
    }
    var concentration = new ArrayList<Map<String, Object>>();
    for (var r : plan.rows())
      if (r.weightPct() > cfg.concentrationLimitPct())
        concentration.add(Map.of("ticker", r.ticker(), "weightPct", r2(r.weightPct()), "limitPct", cfg.concentrationLimitPct()));

    var stress = new ArrayList<Map<String, Object>>();
    for (var s : StressTest.run(values, cfg.classes() == null ? Map.of() : cfg.classes(), cfg.stress()))
      stress.add(Map.of("scenario", s.scenario(), "lossUsd", r2(s.lossUsd()), "lossGtq", r2(s.lossUsd() * fx), "lossPct", r2(s.lossPct())));

    // ---- meta ----
    double totalGtq = totalUsd * fx;
    var proj = Projection.toGoal(totalGtq, cfg.monthlyContributionGtq(), cfg.expectedAnnualReturnPct() / 100, cfg.goalGtq(), in.today());
    var goal = new LinkedHashMap<String, Object>();
    goal.put("goalGtq", cfg.goalGtq());
    goal.put("progressPct", r2(proj.progressPct()));
    goal.put("monthsLeft", proj.months());
    goal.put("etaDate", proj.eta() == null ? null : proj.eta().toString());
    goal.put("assumedAnnualReturnPct", cfg.expectedAnnualReturnPct());

    var portfolio = new LinkedHashMap<String, Object>();
    portfolio.put("hasPositions", hasPositions);
    portfolio.put("valueUsd", r2(totalUsd));
    portfolio.put("valueGtq", r2(totalGtq));
    portfolio.put("costUsd", r2(costUsd));
    portfolio.put("dayChangePct", r2(dayChangePct));
    portfolio.put("dayChangeUsd", r2(dayChangeUsd));
    portfolio.put("totalGainUsd", r2(totalUsd - costUsd));
    portfolio.put("totalGainGtq", r2((totalUsd - costUsd) * fx));
    portfolio.put("totalGainPct", costUsd == 0 ? null : r2((totalUsd / costUsd - 1) * 100));
    portfolio.put("goal", goal);
    portfolio.put("holdings", holdings);
    portfolio.put("risk", risk);
    portfolio.put("concentration", concentration);
    portfolio.put("stress", stress);
    portfolio.put("rebalance", Map.of("needed", plan.needed(), "bandPts", cfg.rebalanceBandPts(), "afterMaxDeviationPts", r2(plan.afterMaxDeviationPts())));
    out.put("portfolio", portfolio);
    out.put("calendar", Map.of("monthlyContributionGtq", cfg.monthlyContributionGtq(), "monthlyContributionUsd", r2(monthlyUsd), "buys", buys, "note", plan.needed()
        ? "Algún ETF está fuera de su banda: este aporte va solo a lo que está por debajo del objetivo. No hace falta vender nada."
        : "Todo está dentro de su banda: el aporte sigue los pesos de tu plan."));

    // ---- mercado ----
    var spCloses = spBars.stream().mapToDouble(Types.Bar::close).toArray();
    Double spPrice = spCloses.length == 0 ? null : spCloses[spCloses.length - 1];
    Double spDayPct = spCloses.length < 2 ? null : (spCloses[spCloses.length - 1] / spCloses[spCloses.length - 2] - 1) * 100;
    double spMax = Arrays.stream(spCloses).max().orElse(0);
    Double spDd = spPrice == null || spMax == 0 ? null : (spPrice / spMax - 1) * 100;
    Double sma200 = Metrics.sma(spCloses, 200);
    var hy = in.macro().getOrDefault("BAMLH0A0HYM2", List.of());
    Double hyLast = lastValue(hy);
    Double hyChange = hy.size() > 20 ? hy.getLast().value() - hy.get(hy.size() - 21).value() : null;
    var signals = MarketSignals.evaluate(new MarketSignals.Inputs(lastValue(in.macro().get("VIXCLS")), spDd, spPrice, sma200, lastValue(in.macro().get("T10Y2Y")), hyLast, hyChange));

    var sp = new LinkedHashMap<String, Object>();
    sp.put("price", r2(spPrice));
    sp.put("dayPct", r2(spDayPct));
    sp.put("drawdownPct", r2(spDd));
    sp.put("sma200", r2(sma200));
    sp.put("asOf", asOf.toString());
    var macro = new LinkedHashMap<String, Object>();
    for (var id : FredClient.SERIES) {
      var o = in.macro().get(id);
      if (o != null && !o.isEmpty()) macro.put(id, Map.of("value", o.getLast().value(), "asOf", o.getLast().day().toString()));
    }
    var cpi = in.macro().get("CPIAUCSL");
    if (cpi != null && cpi.size() > 12) macro.put("inflationYoyPct", r2((cpi.getLast().value() / cpi.get(cpi.size() - 13).value() - 1) * 100));
    var indicators = new ArrayList<Map<String, Object>>();
    for (var i : signals.indicators())
      indicators.add(Map.of("id", i.id(), "label", i.label(), "value", i.value(), "state", i.state(), "reading", i.reading(), "means", i.means(), "history", i.history()));
    var market = new LinkedHashMap<String, Object>();
    market.put("overall", signals.overall());
    market.put("summary", signals.summary());
    market.put("indicators", indicators);
    market.put("sp500", sp);
    market.put("macro", macro);
    out.put("market", market);

    // ---- plan para caídas ----
    var drops = DropPlan.evaluate(spDd == null ? 0 : spDd, cfg.dropRules(), cfg.emergencyFundCurrentGtq(), cfg.emergencyFundMinGtq());
    var planOut = new LinkedHashMap<String, Object>();
    planOut.put("drawdownPct", r2(spDd));
    planOut.put("message", drops.message());
    planOut.put("active", drops.active() == null ? null : Map.of("atPct", drops.active().atPct(), "title", drops.active().title(), "action", drops.active().action()));
    planOut.put("next", drops.next() == null ? null : Map.of("atPct", drops.next().atPct(), "title", drops.next().title()));
    planOut.put("emergencyWarning", drops.emergencyWarning());
    planOut.put("rules", cfg.dropRules());
    planOut.put("reminder", DropPlan.REMINDER);
    planOut.put("recovery", in.recovery().stream().map(s -> {
      var m = new LinkedHashMap<String, Object>();
      m.put("thresholdPct", s.thresholdPct());
      m.put("episodes", s.count());
      m.put("medianMonthsToBottom", r2(s.medianMonthsToBottom()));
      m.put("medianMonthsToRecover", r2(s.medianMonthsToRecover()));
      m.put("worstPct", r2(s.worstPct()));
      m.put("stillOpen", s.stillOpen());
      return m;
    }).toList());
    out.put("plan", planOut);

    // ---- narrativa, noticias, gráficas ----
    double safeWeight = plan.rows().stream().filter(r -> List.of("bonds", "cash").contains(cfg.classOf(r.ticker()))).mapToDouble(Rebalancer.Row::weightPct).sum();
    out.put("narrative", Narrative.build(new Narrative.Inputs(spDayPct, dayChangePct, safeWeight, drops, plan.needed(), signals.overall(), hasPositions)));
    out.put("news", in.news().stream().map(n -> {
      var m = new LinkedHashMap<String, Object>();
      m.put("title", n.news().title());
      m.put("url", n.news().url());
      m.put("source", n.news().source());
      m.put("publishedAt", n.news().publishedAt().toString());
      m.put("ticker", n.news().ticker());
      m.put("whyItMatters", n.whyItMatters());
      return m;
    }).toList());
    var charts = new LinkedHashMap<String, Object>();
    charts.put("dates", series.dates);
    charts.put("portfolioUsd", series.value);
    charts.put("sp500Usd", series.benchmark);
    charts.put("contributedUsd", series.contributed);
    charts.put("drawdownPct", series.drawdownPct);
    out.put("charts", charts);
    out.put("sources", in.sources());
    return out;
  }

  private record Series(List<String> dates, List<Double> value, List<Double> benchmark, List<Double> contributed, List<Double> drawdownPct, double[] returns, double[] spReturns, double[] index) {}

  /**
   * Valor diario del portafolio con las compras de cada fecha, su rendimiento sin contar los aportes,
   * y lo que valdría el mismo dinero si se hubiera puesto en el S&P 500.
   */
  private static Series portfolioSeries(Inputs in, Set<String> tickers, Map<String, TreeMap<LocalDate, Double>> prices, List<Types.Bar> spBars) {
    var empty = new Series(List.of(), List.of(), List.of(), List.of(), List.of(), new double[0], new double[0], new double[] {100});
    if (in.positions().isEmpty() || spBars.isEmpty()) return empty;
    LocalDate first = in.positions().stream().map(Types.Position::boughtOn).min(Comparator.naturalOrder()).orElseThrow();
    var days = spBars.stream().map(Types.Bar::day).filter(d -> !d.isBefore(first)).toList();
    // Si la primera compra fue después del último dato, no hay serie todavía.
    if (days.isEmpty()) return empty;
    var spPrice = prices.get(SP);

    var dates = new ArrayList<String>();
    var value = new ArrayList<Double>();
    var bench = new ArrayList<Double>();
    var contributed = new ArrayList<Double>();
    var rets = new ArrayList<Double>();
    var spRets = new ArrayList<Double>();
    double prevValue = 0, prevSp = 0, units = 0, cost = 0;
    var lastPrice = new HashMap<String, Double>();
    for (LocalDate d : days) {
      double v = 0, flow = 0;
      for (var t : tickers) {
        var m = prices.get(t);
        if (m == null) continue;
        var e = m.floorEntry(d);
        if (e == null) continue;
        lastPrice.put(t, e.getValue());
        double sharesHeld = 0, bought = 0;
        for (var p : in.positions()) {
          if (!p.ticker().equals(t)) continue;
          LocalDate effective = days.stream().filter(x -> !x.isBefore(p.boughtOn())).findFirst().orElse(null);
          if (effective == null || effective.isAfter(d)) continue;
          sharesHeld += p.shares();
          if (effective.equals(d)) {
            bought += p.shares();
            cost += p.shares() * p.avgPriceUsd();
          }
        }
        v += sharesHeld * e.getValue();
        flow += bought * e.getValue();
      }
      double sp = spPrice.get(d);
      units += flow / sp;
      if (prevValue > 0) {
        rets.add((v - flow) / prevValue - 1);
        spRets.add(sp / prevSp - 1);
      }
      dates.add(d.toString());
      value.add(Math.round(v * 100) / 100.0);
      bench.add(Math.round(units * sp * 100) / 100.0);
      contributed.add(Math.round(cost * 100) / 100.0);
      prevValue = v;
      prevSp = sp;
    }
    double[] r = rets.stream().mapToDouble(Double::doubleValue).toArray();
    double[] idx = Metrics.index(r);
    var dd = new ArrayList<Double>();
    var ddAll = Metrics.drawdowns(idx);
    dd.add(0.0);
    for (double x : ddAll) dd.add(Math.round(x * 10000) / 100.0);
    // idx tiene un punto más que los rendimientos y que dates[1..]: se alinea con dates.
    while (dd.size() > dates.size()) dd.removeLast();
    return new Series(dates, value, bench, contributed, dd, r, spRets.stream().mapToDouble(Double::doubleValue).toArray(), idx);
  }
}
