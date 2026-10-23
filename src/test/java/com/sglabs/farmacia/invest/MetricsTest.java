package com.sglabs.farmacia.invest;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MetricsTest {
  static final double EPS = 1e-9;

  @Test
  void returnsAreSimpleChanges() {
    assertThat(Metrics.returns(new double[] {100, 110, 99})).containsExactly(new double[] {0.10, -0.10}, offset(EPS));
    assertThat(Metrics.returns(new double[] {100})).isEmpty();
  }

  @Test
  void indexRebuildsTheValuesFromReturns() {
    assertThat(Metrics.index(new double[] {0.10, -0.10})).containsExactly(new double[] {100, 110, 99}, offset(1e-9));
  }

  @Test
  void sampleStandardDeviation() {
    assertThat(Metrics.stdev(new double[] {1, 2, 3, 4})).isCloseTo(1.2909944487, within(1e-9));
    assertThat(Metrics.stdev(new double[] {5})).isZero();
  }

  @Test
  void volatilityIsAnnualizedWithSquareRootOf252() {
    var daily = new double[] {0.01, -0.01, 0.02, 0.0};
    assertThat(Metrics.annualizedVol(daily)).isCloseTo(0.0129099444 * Math.sqrt(252), within(1e-6));
  }

  @Test
  void drawdownIsMeasuredFromTheRunningPeak() {
    assertThat(Metrics.drawdowns(new double[] {100, 120, 90})).containsExactly(new double[] {0, 0, -0.25}, offset(EPS));
    assertThat(Metrics.maxDrawdown(new double[] {100, 120, 90, 130, 65})).isCloseTo(-0.5, within(EPS));
    assertThat(Metrics.maxDrawdown(new double[] {1, 2, 3})).isZero();
  }

  @Test
  void sharpeUsesAnnualReturnMinusRiskFreeOverVolatility() {
    var daily = new double[] {0.01, -0.01, 0.02, 0.0};
    assertThat(Metrics.sharpe(daily, 0.02)).isCloseTo(6.0506, within(0.001));
    assertThat(Metrics.sharpe(new double[] {0.01, 0.01, 0.01}, 0.02)).isNull(); // sin volatilidad no hay Sharpe
  }

  @Test
  void betaIsTheSlopeAgainstTheMarket() {
    var market = new double[] {0.01, -0.02, 0.015, 0.005, -0.01};
    var doubled = new double[market.length];
    for (int i = 0; i < market.length; i++) doubled[i] = market[i] * 2;
    assertThat(Metrics.beta(doubled, market)).isCloseTo(2.0, within(1e-9));
    assertThat(Metrics.beta(market, market)).isCloseTo(1.0, within(1e-9));
    assertThat(Metrics.beta(new double[] {0.1, 0.2}, new double[] {0.0, 0.0})).isNull();
    assertThat(Metrics.beta(new double[] {0.1}, new double[] {0.1, 0.2})).isNull();
  }

  @Test
  void movingAverageNeedsEnoughPoints() {
    assertThat(Metrics.sma(new double[] {1, 2, 3, 4, 5}, 3)).isEqualTo(4.0);
    assertThat(Metrics.sma(new double[] {1, 2}, 3)).isNull();
  }

  @Test
  void cagrCompoundsOverTheElapsedDays() {
    assertThat(Metrics.cagr(100, 121, 730)).isCloseTo(0.10, within(1e-9));
    assertThat(Metrics.cagr(100, 121, 10)).isNull(); // muy pocos días para anualizar
  }
}
