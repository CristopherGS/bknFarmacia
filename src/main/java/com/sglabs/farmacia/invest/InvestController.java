package com.sglabs.farmacia.invest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** API del portafolio personal. Todo exige ser el dueño (INVEST_OWNER); para los demás es un 403. */
@RestController
@RequestMapping("/api/v1/invest")
@PreAuthorize("@perm.investOwner()")
class InvestController {
  record PositionIn(@NotBlank @Pattern(regexp = "[A-Za-z0-9.^-]{1,15}") String ticker, @Positive @DecimalMax("1000000000") double shares, @Positive @DecimalMax("1000000") double avgPriceUsd, @NotNull LocalDate boughtOn) {}

  record ContributionIn(@NotNull LocalDate day, @Positive @DecimalMax("100000000") double gtq, @Positive @DecimalMax("1000") double fx) {}

  private final InvestStore store;
  private final InvestService service;

  InvestController(InvestStore store, InvestService service) {
    this.store = store;
    this.service = service;
  }

  @GetMapping("/latest")
  ResponseEntity<?> latest() {
    var snap = store.latestSnapshot();
    if (snap.isEmpty()) return ResponseEntity.ok(Map.of("empty", true, "message", "Aún no hay datos. Pulsa «Actualizar ahora»."));
    return ResponseEntity.ok().header("Content-Type", "application/json").body(snap.get());
  }

  @GetMapping("/history")
  Object history(@RequestParam(defaultValue = "365") int days) {
    return store.history(LocalDate.now().minusDays(Math.max(1, Math.min(days, 3650))));
  }

  @PostMapping("/refresh")
  ResponseEntity<?> refresh() {
    if (service.status().running()) return ResponseEntity.accepted().body(service.status());
    CompletableFuture.runAsync(service::refresh);
    return ResponseEntity.accepted().body(Map.of("running", true));
  }

  @GetMapping("/status")
  Object status() {
    return service.status();
  }

  @GetMapping("/config")
  Object config() {
    return store.config();
  }

  @PutMapping("/config")
  Object saveConfig(@RequestBody InvestConfig cfg) {
    cfg.validate();
    store.saveConfig(cfg);
    return cfg;
  }

  @GetMapping("/positions")
  Object positions() {
    return store.positions();
  }

  @PostMapping("/positions")
  Object addPosition(@Valid @RequestBody PositionIn p) {
    return Map.of("id", store.addPosition(p.ticker().toUpperCase(Locale.ROOT), p.shares(), p.avgPriceUsd(), p.boughtOn()));
  }

  @PutMapping("/positions/{id}")
  Object updatePosition(@PathVariable long id, @Valid @RequestBody PositionIn p) {
    if (!store.updatePosition(id, p.ticker().toUpperCase(Locale.ROOT), p.shares(), p.avgPriceUsd(), p.boughtOn()))
      throw new org.springframework.dao.EmptyResultDataAccessException(1);
    return Map.of("ok", true);
  }

  @DeleteMapping("/positions/{id}")
  Object deletePosition(@PathVariable long id) {
    if (!store.deletePosition(id)) throw new org.springframework.dao.EmptyResultDataAccessException(1);
    return Map.of("ok", true);
  }

  @GetMapping("/contributions")
  Object contributions() {
    return store.contributions();
  }

  @PostMapping("/contributions")
  Object addContribution(@Valid @RequestBody ContributionIn c) {
    double usd = Math.round(c.gtq() / c.fx() * 100) / 100.0;
    return Map.of("id", store.addContribution(c.day(), c.gtq(), usd, c.fx()));
  }

  @DeleteMapping("/contributions/{id}")
  Object deleteContribution(@PathVariable long id) {
    if (!store.deleteContribution(id)) throw new org.springframework.dao.EmptyResultDataAccessException(1);
    return Map.of("ok", true);
  }
}
