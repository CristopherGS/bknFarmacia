package com.sglabs.farmacia.invest;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Aviso opcional por Telegram. Sin token o sin chat, no hace nada. */
final class Telegram {
  private static final Logger LOG = LoggerFactory.getLogger(Telegram.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private final HttpFetcher http;
  private final String base;
  private final String token;
  private final String chat;

  Telegram(HttpFetcher http, String base, String token, String chat) {
    this.http = http;
    this.base = base;
    this.token = token;
    this.chat = chat;
  }

  boolean enabled() {
    return token != null && !token.isBlank() && chat != null && !chat.isBlank();
  }

  /** Nunca lanza: un aviso que falla no debe tumbar la actualización diaria. */
  boolean send(String text) {
    if (!enabled()) return false;
    try {
      http.post(base + "/bot" + token + "/sendMessage", JSON.writeValueAsString(Map.of("chat_id", chat, "text", text)), Map.of("Content-Type", "application/json"));
      return true;
    } catch (Exception e) {
      LOG.warn("No se pudo enviar el aviso por Telegram: {}", e.getMessage());
      return false;
    }
  }
}
