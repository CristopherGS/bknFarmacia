package com.sglabs.farmacia.invest;

import java.time.LocalDate;

/** Proyección simple del tiempo para llegar a una meta con aportes mensuales. */
final class Projection {
  record Result(Integer months, LocalDate eta, double progressPct) {}

  private Projection() {}

  /** @param annualReturn rendimiento anual esperado, por ejemplo 0.06 */
  static Result toGoal(double current, double monthly, double annualReturn, double goal, LocalDate today) {
    double progress = goal <= 0 ? 0 : Math.min(100, current / goal * 100);
    if (goal <= 0) return new Result(null, null, 0);
    if (current >= goal) return new Result(0, today, 100);
    double r = Math.pow(1 + annualReturn, 1.0 / 12) - 1;
    double value = current;
    for (int m = 1; m <= 1200; m++) {
      value = value * (1 + r) + monthly;
      if (value >= goal) return new Result(m, today.plusMonths(m), progress);
    }
    return new Result(null, null, progress);
  }
}
