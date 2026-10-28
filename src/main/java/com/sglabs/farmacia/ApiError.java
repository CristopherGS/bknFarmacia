package com.sglabs.farmacia;

import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiError {
  private static final Logger LOG = LoggerFactory.getLogger(ApiError.class);

  @ExceptionHandler(org.springframework.dao.EmptyResultDataAccessException.class)
  ResponseEntity<?> missing() {
    LOG.warn("API 404: registro no encontrado");
    return ResponseEntity.status(404).body(Map.of("message", "Registro no encontrado."));
  }

  @ExceptionHandler(ArithmeticException.class)
  ResponseEntity<?> overflow() {
    return ResponseEntity.badRequest()
        .body(Map.of("message", "Cantidad fuera del rango permitido."));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<?> invalid(IllegalArgumentException e) {
    LOG.warn("API 400: {}", e.getMessage());
    return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
  }

  @ExceptionHandler(IllegalStateException.class)
  ResponseEntity<?> conflict(IllegalStateException e) {
    LOG.warn("API 409: {}", e.getMessage());
    return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<?> duplicate() {
    LOG.warn("API 409: violación de integridad de datos");
    return ResponseEntity.status(409)
        .body(Map.of("message", "Registro duplicado o referencia inválida. Revise los datos."));
  }

  @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
  ResponseEntity<?> tooLarge() {
    LOG.warn("API 413: archivo demasiado grande");
    return ResponseEntity.status(413).body(Map.of("message", "La factura supera 8 MB."));
  }

  @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
  ResponseEntity<?> validation() {
    LOG.warn("API 400: validación de argumentos fallida");
    return ResponseEntity.badRequest()
        .body(Map.of("message", "Revise los campos obligatorios y cantidades."));
  }
}
