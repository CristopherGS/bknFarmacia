package com.sglabs.farmacia;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InvoiceMatcherTest {
  static InvoiceMatcher.Candidate c(long id, long product, String name, String generic, String presentation, String sku, String barcode) {
    return new InvoiceMatcher.Candidate(id, product, name, generic, presentation, sku, barcode);
  }

  final List<InvoiceMatcher.Candidate> catalog =
      List.of(
          c(1, 10, "Acetaminofén 500 mg", "Paracetamol", "Caja x100", "ACE-500", "7400000000011"),
          c(2, 10, "Acetaminofén 500 mg", "Paracetamol", "Blíster x10", "ACE-500", "7400000000028"),
          c(3, 20, "Amoxicilina 500 mg", "Amoxicilina", "Caja x21", "AMX-500", ""),
          c(4, 30, "Ibuprofeno 400 mg", "Ibuprofeno", "Caja x20", "IBU-400", ""),
          c(5, 31, "Ibuprofeno 600 mg", "Ibuprofeno", "Caja x20", "IBU-600", ""));

  @Test
  void separatesLettersFromNumbersAndIgnoresAccentsAndFillerWords() {
    assertThat(InvoiceMatcher.tokens("ACETAMINOFÉN 500MG TAB. CAJA X100"))
        .containsExactly("acetaminofen", "500", "tab", "caja", "100");
    assertThat(InvoiceMatcher.tokens(null)).isEmpty();
  }

  @Test
  void barcodeIsExactEvenWithSpacesOrDashes() {
    var m = InvoiceMatcher.match("cosa rara", "", "7400-000 000028", catalog);
    assertThat(m.presentationId()).isEqualTo(2);
    assertThat(m.method()).isEqualTo("BARCODE");
    assertThat(m.confidence()).isEqualTo(1.0);
  }

  @Test
  void skuPicksThePresentationNamedInTheDescription() {
    var m = InvoiceMatcher.match("ACE-500 caja x100", "ace-500", "", catalog);
    assertThat(m.presentationId()).isEqualTo(1);
    assertThat(m.method()).isEqualTo("SKU");
  }

  @Test
  void skuWithSeveralPresentationsAndNoHintIsNotGuessed() {
    assertThat(InvoiceMatcher.match("acetaminofen", "ACE-500", "", catalog).presentationId()).isNull();
  }

  @Test
  void skuWithASinglePresentationIsAccepted() {
    var m = InvoiceMatcher.match("lo que sea", "AMX-500", "", catalog);
    assertThat(m.presentationId()).isEqualTo(3);
    assertThat(m.confidence()).isEqualTo(0.95);
  }

  @Test
  void nameMatchingFindsTheProductFromAnInvoiceDescription() {
    var m = InvoiceMatcher.match("AMOXICILINA 500MG CAJA X21", "", "", catalog);
    assertThat(m.presentationId()).isEqualTo(3);
    assertThat(m.method()).isEqualTo("NAME");
    assertThat(m.confidence()).isGreaterThan(0.8);
  }

  @Test
  void aDifferentStrengthIsNotTheSameProduct() {
    // 600 mg debe ir al de 600 y no al de 400, aunque comparten casi todo el nombre.
    assertThat(InvoiceMatcher.match("IBUPROFENO 600 MG CAJA X20", "", "", catalog).presentationId()).isEqualTo(5);
    assertThat(InvoiceMatcher.match("IBUPROFENO 400 MG CAJA X20", "", "", catalog).presentationId()).isEqualTo(4);
  }

  @Test
  void ambiguousOrUnrelatedDescriptionsAreLeftForAPerson() {
    assertThat(InvoiceMatcher.match("IBUPROFENO", "", "", catalog).presentationId()).isNull(); // 400 o 600
    assertThat(InvoiceMatcher.match("Servicio de transporte", "", "", catalog).presentationId()).isNull();
    assertThat(InvoiceMatcher.match("", "", "", catalog)).isEqualTo(InvoiceMatcher.NONE);
  }

  @Test
  void matchesSuppliersByNitOrByVeryCloseName() {
    var suppliers =
        List.<Map<String, Object>>of(
            Map.of("id", 1L, "name", "Distribuidora Central, S.A.", "nit", "1234567-8"),
            Map.of("id", 2L, "name", "Droguería Norte", "nit", ""));
    assertThat(InvoiceMatcher.matchSupplier("12345678", "otro nombre", suppliers)).isEqualTo(1L);
    assertThat(InvoiceMatcher.matchSupplier("", "DISTRIBUIDORA CENTRAL SA", suppliers)).isEqualTo(1L);
    assertThat(InvoiceMatcher.matchSupplier("", "Farmacia La Esquina", suppliers)).isNull();
  }
}
