package com.sglabs.farmacia.invest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Actualización diaria después del cierre del mercado de EE. UU. Solo corre si hay un dueño configurado. */
@Component
class InvestJob {
  private final InvestService service;
  private final boolean enabled;

  InvestJob(InvestService service, @Value("${app.invest.owner:}") String owner) {
    this.service = service;
    this.enabled = owner != null && !owner.isBlank();
  }

  @Scheduled(cron = "${app.invest.cron:0 30 22 * * MON-FRI}", zone = "UTC")
  void daily() {
    if (enabled) service.refresh();
  }
}
