package com.sglabs.farmacia;

import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
public class CashScheduler {
  private static final Logger LOG = LoggerFactory.getLogger(CashScheduler.class);
  private final CashAutomation cash;
  private final JdbcTemplate db;

  @org.springframework.context.annotation.Bean
  public static Clock businessClock() {
    return Clock.systemUTC();
  }

  public CashScheduler(CashAutomation cash, JdbcTemplate db) {
    this.cash = cash;
    this.db = db;
  }

  @Scheduled(cron = "0 0 0,6 * * *", zone = "America/Guatemala")
  public void atBoundary() {
    reconcile();
  }

  // Also run at startup and recover after outages, independently of browser sessions.
  @Scheduled(fixedDelay = 15000, initialDelay = 1500)
  public void reconcile() {
    if (!cash.enabled()) return;
    for (Long branch : db.queryForList("SELECT id FROM branches ORDER BY id", Long.class)) {
      try {
        cash.synchronizeBranch(branch, Instant.now());
      } catch (RuntimeException ex) {
        LOG.error("No se pudo sincronizar caja automática de sucursal {}", branch, ex);
      }
    }
  }
}
