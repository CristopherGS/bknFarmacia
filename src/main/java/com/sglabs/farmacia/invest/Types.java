package com.sglabs.farmacia.invest;

import java.time.Instant;
import java.time.LocalDate;

/** Datos que viajan entre las fuentes, la base y los cálculos. */
final class Types {
  private Types() {}

  /** Una vela diaria. adjClose incluye dividendos reinvertidos (rendimiento total). */
  record Bar(LocalDate day, double close, double adjClose) {}

  record Obs(LocalDate day, double value) {}

  record News(String ticker, String title, String url, String source, Instant publishedAt, String summary) {}

  record Fx(double gtqPerUsd, LocalDate asOf, String source, boolean stale) {}

  record Position(long id, String ticker, double shares, double avgPriceUsd, LocalDate boughtOn) {}

  record Contribution(long id, LocalDate day, double gtq, double usd, double fx) {}
}
