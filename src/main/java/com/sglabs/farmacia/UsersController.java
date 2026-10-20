package com.sglabs.farmacia;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
@PreAuthorize("hasRole('ADMIN')")
public class UsersController {
  final Operations op;
  final org.springframework.jdbc.core.JdbcTemplate db;
  final PasswordEncoder encoder;

  UsersController(
      Operations op, PasswordEncoder encoder, org.springframework.jdbc.core.JdbcTemplate db) {
    this.db = db;
    this.op = op;
    this.encoder = encoder;
  }

  record NewUser(
      @Pattern(regexp = "[a-zA-Z0-9._-]{3,80}") @NotNull String username,
      @Size(min = 14, max = 72) @NotNull String password,
      @Pattern(regexp = "ADMIN|SUPERVISOR|CASHIER|INVENTORY|ACCOUNTANT") @NotNull String role) {}

  @GetMapping
  Object list(Principal p) {
    return db.queryForList(
        "SELECT id,username,role,active FROM users WHERE branch_id=? ORDER BY username",
        op.branch(p.getName()));
  }

  @PostMapping
  @Transactional
  Object create(@Valid @RequestBody NewUser u, Principal p) {
    long branch = op.branch(p.getName());
    db.update(
        "INSERT INTO users(username,password_hash,role,branch_id) VALUES (?,?,?,?)",
        u.username(),
        encoder.encode(u.password()),
        u.role(),
        branch);
    op.audit(branch, p.getName(), "USER_CREATED", u.username());
    return Map.of("ok", true);
  }

  record PasswordChange(@Size(min = 14, max = 72) @NotNull String password) {}

  @PostMapping("/password")
  @Transactional
  Object password(@Valid @RequestBody PasswordChange change, Principal p) {
    db.update(
        "UPDATE users SET password_hash=? WHERE username=?",
        encoder.encode(change.password()),
        p.getName());
    op.audit(op.branch(p.getName()), p.getName(), "PASSWORD_CHANGED", p.getName());
    return Map.of("ok", true);
  }
}
