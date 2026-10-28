package com.sglabs.farmacia;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.Principal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Borradores de compra a partir de facturas. La persona sube la imagen; n8n (u otro lector) la
 * descarga con su llave, extrae las líneas y las devuelve; el sistema las cruza con el catálogo y una
 * persona con permiso de compras las revisa y confirma. Nada llega al inventario sin ese paso.
 */
@RestController
@RequestMapping("/api/v1/receipts/drafts")
public class InvoiceDraftsController {
  private static final Logger LOG = LoggerFactory.getLogger(InvoiceDraftsController.class);

  static final int MAX_BYTES = 8 * 1024 * 1024;
  static final int MAX_OPEN_DRAFTS = 50;

  private final JdbcTemplate db;
  private final Operations op;
  private final Events events;

  InvoiceDraftsController(JdbcTemplate db, Operations op, Events events) {
    this.db = db;
    this.op = op;
    this.events = events;
  }

  record ExtractedLine(
      @NotBlank @Size(max = 300) String description,
      @Size(max = 80) String sku,
      @Size(max = 100) String barcode,
      @DecimalMin("0") BigDecimal quantity,
      @Size(max = 100) String lot,
      @Size(max = 20) String expiresOn,
      @DecimalMin("0") BigDecimal totalCost) {}

  record Extraction(
      @Size(max = 300) String error,
      @Size(max = 200) String supplierName,
      @Size(max = 40) String supplierNit,
      @Size(max = 100) String document,
      @Size(max = 200) List<@Valid ExtractedLine> lines) {}

  /** Tipo real del archivo según sus primeros bytes; el nombre o el encabezado no se creen. */
  static String sniff(byte[] b) {
    if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF)
      return "image/jpeg";
    if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G')
      return "image/png";
    if (b.length >= 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') return "application/pdf";
    if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P')
      return "image/webp";
    return null;
  }

  static String safeName(String original) {
    String name = original == null ? "factura" : original.replaceAll(".*[/\\\\]", "");
    name = name.replaceAll("[^\\p{L}\\p{N}._ -]", "_").trim();
    if (name.isEmpty()) name = "factura";
    return name.length() > 200 ? name.substring(name.length() - 200) : name;
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("@perm.has('INVOICES_IMPORT')")
  @Transactional
  Object upload(@RequestParam("file") MultipartFile file, Principal p) throws IOException {
    long branch = op.branch(p.getName());
    if (file.isEmpty()) throw new IllegalArgumentException("El archivo está vacío.");
    if (file.getSize() > MAX_BYTES) throw new IllegalArgumentException("La factura supera 8 MB.");
    byte[] bytes = file.getBytes();
    String type = sniff(bytes);
    if (type == null)
      throw new IllegalArgumentException("Formato no admitido. Sube una foto JPG, PNG o WEBP, o un PDF.");
    if (!events.enabled())
      throw new IllegalStateException(
          "Conecta n8n en Configuración para leer facturas automáticamente.");
    int open =
        db.queryForObject(
            "SELECT COUNT(*) FROM receipt_drafts WHERE branch_id=? AND status IN ('PENDING','READY')",
            Integer.class,
            branch);
    if (open >= MAX_OPEN_DRAFTS)
      throw new IllegalStateException("Hay demasiadas facturas sin revisar. Confirma o descarta algunas.");
    String name = safeName(file.getOriginalFilename());
    long id =
        db.queryForObject(
            "INSERT INTO receipt_drafts(branch_id,filename,content_type,size_bytes,created_by) VALUES"
                + " (?,?,?,?,?) RETURNING id",
            Long.class,
            branch,
            name,
            type,
            bytes.length,
            p.getName());
    db.update("INSERT INTO invoice_files(draft_id,bytes) VALUES (?,?)", id, bytes);
    op.audit(branch, p.getName(), "INVOICE_UPLOADED", Long.toString(id));
    events.emit(
        "invoice.uploaded",
        id,
        Map.of(
            "draftId", id,
            "filename", name,
            "contentType", type,
            "sizeBytes", bytes.length,
            "uploadedBy", p.getName(),
            "filePath", "/api/v1/receipts/drafts/" + id + "/file",
            "extractionPath", "/api/v1/receipts/drafts/" + id + "/extraction"));
    LOG.info(
        "Factura enviada a n8n: draftId={}, tipo={}, bytes={}, usuario={}",
        id,
        type,
        bytes.length,
        p.getName());
    return Map.of("id", id, "status", "PENDING");
  }

  @GetMapping
  @PreAuthorize("@perm.any('INVOICES_IMPORT','PURCHASES_RECEIVE')")
  Object list(Principal p) {
    return db.queryForList(
        "SELECT d.id,d.status,d.filename,d.content_type,d.document,d.supplier_text,d.error,d.created_by,"
            + "d.created_at,d.receipt_id,(SELECT COUNT(*) FROM receipt_draft_lines l WHERE"
            + " l.draft_id=d.id) AS line_count FROM receipt_drafts d WHERE d.branch_id=? ORDER BY d.id"
            + " DESC LIMIT 30",
        op.branch(p.getName()));
  }

  private Map<String, Object> draft(long id, Principal p) {
    return db.queryForMap(
        "SELECT * FROM receipt_drafts WHERE id=? AND branch_id=?", id, op.branch(p.getName()));
  }

  @GetMapping("/{id}")
  @PreAuthorize("@perm.any('INVOICES_IMPORT','PURCHASES_RECEIVE')")
  Object get(@PathVariable long id, Principal p) {
    var d = draft(id, p);
    var out = new LinkedHashMap<String, Object>(d);
    out.put(
        "lines",
        db.queryForList(
            "SELECT l.*,pr.name AS presentation_name,p.name AS product_name FROM receipt_draft_lines l"
                + " LEFT JOIN presentations pr ON pr.id=l.presentation_id LEFT JOIN products p ON"
                + " p.id=pr.product_id WHERE l.draft_id=? ORDER BY l.position",
            id));
    return out;
  }

  @GetMapping("/{id}/file")
  @PreAuthorize("@perm.any('INVOICES_IMPORT','PURCHASES_RECEIVE')")
  ResponseEntity<byte[]> file(@PathVariable long id, Principal p) {
    var d = draft(id, p);
    var rows = db.queryForList("SELECT bytes FROM invoice_files WHERE draft_id=?", id);
    if (rows.isEmpty()) throw new org.springframework.dao.EmptyResultDataAccessException(1);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType((String) d.get("content_type")))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + d.get("filename") + "\"")
        .header("X-Content-Type-Options", "nosniff")
        .body((byte[]) rows.getFirst().get("bytes"));
  }

  /** Lo que el lector (n8n) devuelve: encabezado y líneas, o el motivo por el que no pudo leerla. */
  @PutMapping("/{id}/extraction")
  @PreAuthorize("@perm.has('INVOICES_IMPORT')")
  @Transactional
  Object extraction(@PathVariable long id, @Valid @RequestBody Extraction x, Principal p) {
    long branch = op.branch(p.getName());
    var d =
        db.queryForMap(
            "SELECT status FROM receipt_drafts WHERE id=? AND branch_id=? FOR UPDATE", id, branch);
    String status = (String) d.get("status");
    if (!List.of("PENDING", "READY", "FAILED").contains(status))
      throw new IllegalStateException("Esta factura ya fue " + ("CONFIRMED".equals(status) ? "confirmada." : "descartada."));
    if (x.error() != null && !x.error().isBlank()) {
      db.update(
          "UPDATE receipt_drafts SET status='FAILED',error=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
          x.error().trim(),
          id);
      op.audit(branch, p.getName(), "INVOICE_READ_FAILED", Long.toString(id));
      events.emit("invoice.failed", id, Map.of("draftId", id, "error", x.error().trim()));
      LOG.warn("n8n no pudo leer factura: draftId={}, motivo={}", id, x.error().trim());
      return Map.of("ok", true, "status", "FAILED");
    }
    if (x.lines() == null || x.lines().isEmpty())
      throw new IllegalArgumentException("La lectura no trae líneas. Envía `error` si no se pudo leer.");

    var catalog =
        db.query(
            "SELECT pr.id,pr.product_id,p.name,p.generic_name,pr.name AS presentation,p.sku,pr.barcode FROM"
                + " presentations pr JOIN products p ON p.id=pr.product_id WHERE p.active=TRUE",
            (r, n) ->
                new InvoiceMatcher.Candidate(
                    r.getLong("id"),
                    r.getLong("product_id"),
                    r.getString("name"),
                    r.getString("generic_name"),
                    r.getString("presentation"),
                    r.getString("sku"),
                    r.getString("barcode")));
    Long supplier =
        InvoiceMatcher.matchSupplier(
            x.supplierNit(), x.supplierName(), db.queryForList("SELECT id,name,nit FROM suppliers"));
    db.update("DELETE FROM receipt_draft_lines WHERE draft_id=?", id);
    int position = 0, matched = 0;
    for (var l : x.lines()) {
      var m = InvoiceMatcher.match(l.description(), l.sku(), l.barcode(), catalog);
      if (m.presentationId() != null) matched++;
      db.update(
          "INSERT INTO receipt_draft_lines(draft_id,position,description,sku,barcode,quantity,lot,"
              + "expires_on,total_cost,presentation_id,confidence,method) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
          id,
          position++,
          l.description().trim(),
          Objects.toString(l.sku(), "").trim(),
          Objects.toString(l.barcode(), "").trim(),
          l.quantity() == null ? null : l.quantity().setScale(3, RoundingMode.HALF_UP),
          Objects.toString(l.lot(), "").trim(),
          parseDate(l.expiresOn()),
          l.totalCost() == null ? null : l.totalCost().setScale(2, RoundingMode.HALF_UP),
          m.presentationId(),
          m.confidence(),
          m.method());
    }
    db.update(
        "UPDATE receipt_drafts SET status='READY',error='',supplier_id=?,supplier_text=?,document=?,"
            + "updated_at=CURRENT_TIMESTAMP WHERE id=?",
        supplier,
        Objects.toString(x.supplierName(), "").trim(),
        Objects.toString(x.document(), "").trim(),
        id);
    op.audit(branch, p.getName(), "INVOICE_READ", Long.toString(id));
    events.emit(
        "invoice.ready",
        id,
        Map.of("draftId", id, "lines", x.lines().size(), "matched", matched, "supplierMatched", supplier != null));
    LOG.info(
        "Extracción de factura recibida desde n8n: draftId={}, líneas={}, coincidencias={}, proveedorCoincide={}",
        id,
        x.lines().size(),
        matched,
        supplier != null);
    return Map.of("ok", true, "status", "READY", "lines", x.lines().size(), "matched", matched);
  }

  private static LocalDate parseDate(String s) {
    if (s == null || s.isBlank()) return null;
    try {
      return LocalDate.parse(s.trim());
    } catch (DateTimeParseException e) {
      return null; // un texto que no es fecha ISO queda en blanco para que la persona lo complete
    }
  }

  /** La persona revisó y corrigió el borrador: se registra la compra con el mismo flujo de siempre. */
  @PostMapping("/{id}/confirm")
  @PreAuthorize("@perm.has('PURCHASES_RECEIVE')")
  @Transactional
  Object confirm(@PathVariable long id, @Valid @RequestBody Operations.Receipt receipt, Principal p) {
    long branch = op.branch(p.getName());
    var d =
        db.queryForMap(
            "SELECT status FROM receipt_drafts WHERE id=? AND branch_id=? FOR UPDATE", id, branch);
    if (!"READY".equals(d.get("status")))
      throw new IllegalStateException("Esta factura no está lista para confirmar.");
    long receiptId = op.receive(receipt, p.getName());
    db.update(
        "UPDATE receipt_drafts SET status='CONFIRMED',receipt_id=?,updated_at=CURRENT_TIMESTAMP WHERE"
            + " id=?",
        receiptId,
        id);
    op.audit(branch, p.getName(), "INVOICE_CONFIRMED", Long.toString(id));
    return Map.of("id", receiptId);
  }

  @PostMapping("/{id}/discard")
  @PreAuthorize("@perm.any('INVOICES_IMPORT','PURCHASES_RECEIVE')")
  @Transactional
  Object discard(@PathVariable long id, Principal p) {
    long branch = op.branch(p.getName());
    var d =
        db.queryForMap(
            "SELECT status FROM receipt_drafts WHERE id=? AND branch_id=? FOR UPDATE", id, branch);
    if ("CONFIRMED".equals(d.get("status")))
      throw new IllegalStateException("Una factura confirmada no se puede descartar.");
    db.update(
        "UPDATE receipt_drafts SET status='DISCARDED',updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
    op.audit(branch, p.getName(), "INVOICE_DISCARDED", Long.toString(id));
    return Map.of("ok", true);
  }
}
