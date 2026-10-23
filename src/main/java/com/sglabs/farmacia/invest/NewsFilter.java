package com.sglabs.farmacia.invest;

import java.util.*;

/** Deja los titulares que le importan a un inversor de largo plazo y explica por qué (o por qué no). */
final class NewsFilter {
  record Item(Types.News news, String whyItMatters, int score) {}

  private record Topic(List<String> words, int score, String why) {}

  private static final List<Topic> TOPICS =
      List.of(
          new Topic(List.of("fed ", "federal reserve", "powell", "interest rate", "rate cut", "rate hike", "fomc"), 5, "Las tasas de la Fed mueven bonos y acciones. A largo plazo importa la tendencia, no cada reunión."),
          new Topic(List.of("inflation", "cpi", "consumer price"), 5, "La inflación influye en las tasas. Un dato suelto casi nunca cambia un plan de largo plazo."),
          new Topic(List.of("recession", "gdp", "unemployment", "jobs report", "payrolls"), 4, "Muestra la salud de la economía. Las recesiones pasan y los mercados se han recuperado de todas."),
          new Topic(List.of("treasury", "yield", "bond"), 3, "Afecta a tus bonos (BND, SGOV). Los rendimientos más altos hoy significan mejores rendimientos futuros."),
          new Topic(List.of("s&p 500", "stock market", "wall street", "nasdaq", "dow "), 3, "Es el movimiento general de la bolsa, que ya refleja tu parte en acciones."),
          new Topic(List.of("tariff", "trade war", "sanction"), 3, "Puede mover los mercados un tiempo, pero las empresas del índice se adaptan."),
          new Topic(List.of("etf", "index fund", "vanguard"), 3, "Toca directamente a productos como los tuyos."),
          new Topic(List.of("earnings", "profit", "revenue"), 1, "Es sobre una empresa concreta: tus ETFs están diversificados en miles."),
          new Topic(List.of("crash", "plunge", "tumble", "selloff", "sell-off"), 2, "Los titulares dramáticos exageran el día a día. Mira tu plan para caídas, no el titular."));

  private NewsFilter() {}

  static String whyFor(Types.News n) {
    String text = (n.title() + " " + n.summary()).toLowerCase(Locale.ROOT) + " ";
    for (var t : TOPICS) for (var w : t.words()) if (text.contains(w)) return t.why();
    return "Ruido de corto plazo: rara vez cambia un plan de largo plazo.";
  }

  static int score(Types.News n, Set<String> myTickers) {
    String text = (n.title() + " " + n.summary()).toLowerCase(Locale.ROOT) + " ";
    int s = 0;
    for (var t : TOPICS) for (var w : t.words()) if (text.contains(w)) { s = Math.max(s, t.score()); break; }
    if (!n.ticker().isEmpty() && myTickers.contains(n.ticker())) s += 2;
    return s;
  }

  /** Quita duplicados, ordena por relevancia y luego por fecha, y se queda con `max`. */
  static List<Item> pick(Collection<Types.News> all, Set<String> myTickers, int max) {
    var seen = new HashSet<String>();
    var items = new ArrayList<Item>();
    for (var n : all) {
      String key = n.title().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
      if (!seen.add(n.url()) || !seen.add(key)) continue;
      int s = score(n, myTickers);
      if (s > 0) items.add(new Item(n, whyFor(n), s));
    }
    items.sort(Comparator.comparingInt(Item::score).reversed().thenComparing(i -> i.news().publishedAt(), Comparator.reverseOrder()));
    return items.size() > max ? items.subList(0, max) : items;
  }
}
