package com.sglabs.farmacia;

import java.util.*;

/**
 * Catálogo de permisos que el administrador asigna a cada usuario y plantillas iniciales por rol. El
 * frontend lee este catálogo de /api/v1/users/permissions, así que esta clase es la única fuente.
 * ADMIN tiene todos los permisos y la administración de usuarios es exclusiva de ADMIN.
 */
public final class Permissions {
  public record Def(String key, String group, String label) {}

  public static final List<Def> ALL =
      List.of(
          new Def("POS_SELL", "Ventas", "Vender en el punto de venta"),
          new Def("SALES_VIEW", "Ventas", "Ver el historial de ventas y comprobantes"),
          new Def("CASH_VIEW", "Caja", "Ver los turnos de caja"),
          new Def("CASH_MANAGE", "Caja", "Abrir y cerrar caja"),
          new Def("CATALOG_VIEW", "Catálogo", "Ver productos y precios"),
          new Def("CATALOG_MANAGE", "Catálogo", "Crear productos"),
          new Def("INVENTORY_VIEW", "Inventario", "Ver existencias, lotes y movimientos"),
          new Def("COSTS_VIEW", "Inventario", "Ver costos de compra"),
          new Def("PURCHASES_VIEW", "Compras", "Ver las compras recibidas"),
          new Def("PURCHASES_RECEIVE", "Compras", "Registrar compras e ingresar existencias"),
          new Def("SUPPLIERS_VIEW", "Proveedores", "Ver proveedores"),
          new Def("SUPPLIERS_MANAGE", "Proveedores", "Crear proveedores"),
          new Def("AUDIT_VIEW", "Administración", "Ver la auditoría"));

  public static final Set<String> KEYS =
      Collections.unmodifiableSet(
          new LinkedHashSet<>(ALL.stream().map(Def::key).toList()));

  public static final List<String> ROLES =
      List.of("ADMIN", "SUPERVISOR", "CASHIER", "INVENTORY", "ACCOUNTANT");

  private static final Map<String, List<String>> PRESETS =
      Map.of(
          "ADMIN", List.copyOf(KEYS),
          "SUPERVISOR", List.copyOf(KEYS),
          "CASHIER",
              List.of(
                  "POS_SELL",
                  "SALES_VIEW",
                  "CASH_VIEW",
                  "CASH_MANAGE",
                  "CATALOG_VIEW",
                  "INVENTORY_VIEW"),
          "INVENTORY",
              List.of(
                  "CATALOG_VIEW",
                  "INVENTORY_VIEW",
                  "COSTS_VIEW",
                  "PURCHASES_VIEW",
                  "PURCHASES_RECEIVE",
                  "SUPPLIERS_VIEW",
                  "SUPPLIERS_MANAGE"),
          "ACCOUNTANT",
              List.of(
                  "SALES_VIEW",
                  "CASH_VIEW",
                  "CATALOG_VIEW",
                  "INVENTORY_VIEW",
                  "COSTS_VIEW",
                  "PURCHASES_VIEW",
                  "SUPPLIERS_VIEW",
                  "AUDIT_VIEW"));

  /** Un permiso que exige otros para tener sentido: quien crea productos tiene que verlos. */
  public static final Map<String, List<String>> IMPLIES =
      Map.of(
          "CATALOG_MANAGE", List.of("CATALOG_VIEW"),
          "SUPPLIERS_MANAGE", List.of("SUPPLIERS_VIEW"),
          "PURCHASES_RECEIVE", List.of("PURCHASES_VIEW", "CATALOG_VIEW", "SUPPLIERS_VIEW"),
          "CASH_MANAGE", List.of("CASH_VIEW"),
          "COSTS_VIEW", List.of("INVENTORY_VIEW"));

  private Permissions() {}

  /** Permisos que recibe un usuario nuevo con ese rol si el administrador no los personaliza. */
  public static List<String> preset(String role) {
    var p = PRESETS.get(role);
    if (p == null) throw new IllegalArgumentException("Rol desconocido: " + role);
    return p;
  }

  public static Map<String, List<String>> presets() {
    var out = new LinkedHashMap<String, List<String>>();
    for (String r : ROLES) out.put(r, preset(r));
    return out;
  }

  /** Valida, agrega los permisos implícitos y ordena según el catálogo. */
  public static List<String> normalize(Collection<String> requested) {
    for (String k : requested)
      if (!KEYS.contains(k)) throw new IllegalArgumentException("Permiso desconocido: " + k);
    var all = new HashSet<>(requested);
    for (String k : requested) all.addAll(IMPLIES.getOrDefault(k, List.of()));
    return KEYS.stream().filter(all::contains).toList();
  }
}
