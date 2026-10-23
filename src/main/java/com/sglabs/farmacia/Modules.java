package com.sglabs.farmacia;

import java.util.*;

/**
 * Módulos que se pueden prender o apagar por negocio y plantillas por tipo de negocio. El flujo base
 * (catálogo, compras, inventario por lote, ventas) es siempre parte del sistema; los módulos agregan
 * o quitan funciones y permisos sobre él.
 */
public final class Modules {
  public record Def(String key, String label, String description) {}

  public record Type(String key, String label, String noun, List<String> modules) {}

  public static final List<Def> ALL =
      List.of(
          new Def("CASH", "Caja", "Turnos y cierre de caja, con la pantalla de caja y sus permisos."),
          new Def("AUDIT", "Auditoría", "Registro de acciones y cambios, con su pantalla y permiso."),
          new Def(
              "RX",
              "Farmacia: recetas y controlados",
              "Control de receta al vender y marca de productos de venta controlada."));

  /** Módulos en preparación: se muestran en la pantalla pero todavía no se pueden activar. */
  public static final List<Def> ROADMAP =
      List.of(
          new Def("FEL", "Factura electrónica (FEL)", "Emisión con un certificador autorizado."),
          new Def("LOYALTY", "Clientes y fidelización", "Ficha de clientes, puntos y descuentos."),
          new Def("DELIVERY", "Domicilios", "Pedidos a domicilio y su seguimiento."),
          new Def("ACCOUNTING", "Contabilidad", "Cuentas por pagar, por cobrar y reportes."));

  public static final Set<String> KEYS =
      Collections.unmodifiableSet(
          new LinkedHashSet<>(ALL.stream().map(Def::key).toList()));

  public static final List<Type> TYPES =
      List.of(
          new Type("FARMACIA", "Farmacia", "farmacia", List.of("CASH", "AUDIT", "RX")),
          new Type("TIENDA", "Tienda o comercio", "tienda", List.of("CASH", "AUDIT")),
          new Type("OTRO", "Otro negocio", "negocio", List.of("CASH", "AUDIT")));

  private Modules() {}

  public static Type type(String key) {
    return TYPES.stream()
        .filter(t -> t.key().equals(key))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Tipo de negocio desconocido: " + key));
  }

  public static List<String> normalize(Collection<String> requested) {
    for (String k : requested)
      if (!KEYS.contains(k)) throw new IllegalArgumentException("Módulo desconocido: " + k);
    return KEYS.stream().filter(requested::contains).toList();
  }
}
