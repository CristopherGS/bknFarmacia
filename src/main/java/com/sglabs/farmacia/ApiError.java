package com.sglabs.farmacia;

import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiError {
  @ExceptionHandler(org.springframework.dao.EmptyResultDataAccessException.class)
  ResponseEntity<?> missing() {
    return ResponseEntity.status(404).body(Map.of("message", "Registro no encontrado."));
  }

  @ExceptionHandler(ArithmeticException.class)
  ResponseEntity<?> overflow() {
    return ResponseEntity.badRequest()
        .body(Map.of("message", "Cantidad fuera del rango permitido."));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<?> invalid(IllegalArgumentException e) {
    return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
  }

  @ExceptionHandler(IllegalStateException.class)
  ResponseEntity<?> conflict(IllegalStateException e) {
    return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<?> duplicate() {
    return ResponseEntity.status(409)
        .body(Map.of("message", "Registro duplicado o referencia inválida. Revise los datos."));
  }

  @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
  ResponseEntity<?> tooLarge() {
    return ResponseEntity.status(413).body(Map.of("message", "La factura supera 8 MB."));
  }

  @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
  ResponseEntity<?> validation() {
    return ResponseEntity.badRequest()
        .body(Map.of("message", "Revise los campos obligatorios y cantidades."));
  }
}
