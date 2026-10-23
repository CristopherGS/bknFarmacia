package com.sglabs.farmacia;

import java.text.Normalizer;
import java.util.*;

/**
 * Cruza lo que el lector de facturas encontró con el catálogo: primero por código de barras, luego por
 * SKU y por último por parecido del nombre. Ante la duda no adivina: una línea sin cruce queda para que
 * la persona la elija, que es mejor que ingresar existencias en el producto equivocado.
 */
final class InvoiceMatcher {
  record Candidate(
      long presentationId,
      long productId,
      String product,
      String generic,
      String presentation,
      String sku,
      String barcode) {}

  record Result(Long presentationId, double confidence, String method) {}

  static final Result NONE = new Result(null, 0, "");
  /** Por debajo de esto el parecido de nombres no basta para proponer un producto. */
  static final double MIN_NAME_SCORE = 0.45;
  /** Si dos presentaciones puntúan casi igual, no se elige ninguna. */
  static final double MIN_MARGIN = 0.05;

  private static final Set<String> STOP =
      Set.of("de", "del", "la", "el", "los", "las", "con", "para", "por", "en", "y", "mg", "ml", "gr", "g");

  private InvoiceMatcher() {}

  static List<String> tokens(String text) {
    if (text == null) return List.of();
    String t =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT)
            // "500mg" y "x100" se separan en letras y números para poder compararlos.
            .replaceAll("(\\d)([a-z])", "$1 $2")
            .replaceAll("([a-z])(\\d)", "$1 $2")
            .replaceAll("[^a-z0-9]+", " ")
            .trim();
    var out = new LinkedHashSet<String>();
    for (String w : t.split(" "))
      if (!w.isEmpty() && !STOP.contains(w) && (w.length() > 1 || Character.isDigit(w.charAt(0))))
        out.add(w);
    return List.copyOf(out);
  }

  static double dice(Collection<String> a, Collection<String> b) {
    if (a.isEmpty() || b.isEmpty()) return 0;
    var sa = new HashSet<>(a);
    var sb = new HashSet<>(b);
    int common = 0;
    for (String w : sa) if (sb.contains(w)) common++;
    return 2.0 * common / (sa.size() + sb.size());
  }

  static String digits(String s) {
    return s == null ? "" : s.replaceAll("\\D+", "");
  }

  static Result match(String description, String sku, String barcode, List<Candidate> catalog) {
    String code = digits(barcode);
    if (!code.isEmpty())
      for (var c : catalog)
        if (digits(c.barcode()).equals(code)) return new Result(c.presentationId(), 1.0, "BARCODE");

    var descTokens = tokens(description);
    String skuKey = sku == null ? "" : sku.trim().toLowerCase(Locale.ROOT);
    if (!skuKey.isEmpty()) {
      var sameSku =
          catalog.stream().filter(c -> c.sku() != null && c.sku().trim().toLowerCase(Locale.ROOT).equals(skuKey)).toList();
      if (sameSku.size() == 1) return new Result(sameSku.getFirst().presentationId(), 0.95, "SKU");
      if (sameSku.size() > 1) {
        // Un producto con varias presentaciones: decide el nombre de la presentación que aparece.
        Candidate best = null;
        double top = 0, second = 0;
        for (var c : sameSku) {
          double s = dice(descTokens, tokens(c.presentation()));
          if (s > top) {
            second = top;
            top = s;
            best = c;
          } else if (s > second) second = s;
        }
        if (best != null && top > 0 && top - second >= MIN_MARGIN)
          return new Result(best.presentationId(), 0.85, "SKU");
        return NONE;
      }
    }

    Candidate best = null;
    double top = 0, second = 0;
    for (var c : catalog) {
      var presentation = tokens(c.presentation());
      var byProduct = new ArrayList<>(tokens(c.product()));
      byProduct.addAll(presentation);
      var byGeneric = new ArrayList<>(tokens(c.generic()));
      byGeneric.addAll(presentation);
      double s = Math.max(dice(descTokens, byProduct), dice(descTokens, byGeneric));
      if (s > top) {
        second = top;
        top = s;
        best = c;
      } else if (s > second) second = s;
    }
    if (best != null && top >= MIN_NAME_SCORE && top - second >= MIN_MARGIN)
      return new Result(best.presentationId(), Math.round(top * 100) / 100.0, "NAME");
    return NONE;
  }

  /** Proveedor: por NIT exacto, o por nombre muy parecido. */
  static Long matchSupplier(String nit, String name, List<Map<String, Object>> suppliers) {
    String wanted = digits(nit);
    if (!wanted.isEmpty())
      for (var s : suppliers)
        if (digits((String) s.get("nit")).equals(wanted)) return ((Number) s.get("id")).longValue();
    var nameTokens = tokens(name);
    Long best = null;
    double top = 0;
    for (var s : suppliers) {
      double d = dice(nameTokens, tokens((String) s.get("name")));
      if (d > top) {
        top = d;
        best = ((Number) s.get("id")).longValue();
      }
    }
    return top >= 0.6 ? best : null;
  }
}
