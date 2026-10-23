package com.sglabs.farmacia.invest;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Precios con respaldo: prueba cada fuente en orden y guarda lo que obtiene. Si todas fallan, sigue
 * sirviendo lo último guardado y lo marca como atrasado, para que la web nunca se quede sin datos.
 */
final class PriceService {
  private static final Logger LOG = LoggerFactory.getLogger(PriceService.class);
  /** Más allá de esto un dato se considera atrasado (cubre fines de semana largos). */
  static final int STALE_AFTER_DAYS = 5;

  record Series(String ticker, List<Types.Bar> bars, String source, boolean stale, String error) {
    LocalDate asOf() {
      return bars.isEmpty() ? null : bars.getLast().day();
    }
  }

  private final List<PriceSource> chain;
  private final InvestStore store;
  private final java.time.Clock clock;

  PriceService(List<PriceSource> chain, InvestStore store, java.time.Clock clock) {
    this.chain = chain;
    this.store = store;
    this.clock = clock;
  }

  static String rangeFor(LocalDate last, LocalDate today) {
    if (last == null) return "max";
    long gap = ChronoUnit.DAYS.between(last, today);
    return gap > 150 ? "2y" : gap > 20 ? "6mo" : "1mo";
  }

  Series load(String ticker) {
    LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    LocalDate last = store.lastBarDay(ticker);
    String range = rangeFor(last, today);
    var errors = new ArrayList<String>();
    String used = null;
    for (var src : chain) {
      if (src.symbolFor(ticker) == null) continue;
      try {
        var bars = src.fetch(ticker, range);
        if (!src.name().equals(chain.getFirst().name())) {
          // Un respaldo solo aporta los días que faltan: nunca pisa el historial de la fuente principal.
          final LocalDate after = last;
          bars = rescale(ticker, bars.stream().filter(b -> after == null || b.day().isAfter(after)).toList());
        }
        store.upsertBars(ticker, bars, src.name());
        used = src.name();
        break;
      } catch (Exception e) {
        LOG.warn("Precios de {}: {} falló: {}", ticker, src.name(), e.getMessage());
        errors.add(src.name() + ": " + e.getMessage());
      }
    }
    var all = store.bars(ticker);
    LocalDate asOf = all.isEmpty() ? null : all.getLast().day();
    boolean stale = used == null || asOf == null || ChronoUnit.DAYS.between(asOf, today) > STALE_AFTER_DAYS;
    return new Series(ticker, all, used, stale, errors.isEmpty() ? "" : String.join("; ", errors));
  }

  /**
   * Una fuente de respaldo no separa dividendos: se aplica a sus cierres la misma relación
   * adj_close/close que tenía el último dato guardado, para no romper la continuidad de la serie.
   */
  private List<Types.Bar> rescale(String ticker, List<Types.Bar> bars) {
    Double ratio = store.lastAdjRatio(ticker);
    if (ratio == null || ratio <= 0) return bars;
    var out = new ArrayList<Types.Bar>(bars.size());
    for (var b : bars) out.add(new Types.Bar(b.day(), b.close(), b.close() * ratio));
    return out;
  }
}
