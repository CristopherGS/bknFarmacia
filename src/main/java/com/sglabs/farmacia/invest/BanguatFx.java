package com.sglabs.farmacia.invest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

/** Tipo de cambio de referencia del Banco de Guatemala (servicio web público, sin llave). */
final class BanguatFx {
  private static final String NS = "http://www.banguat.gob.gt/variables/ws/";
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

  private final HttpFetcher http;
  private final String url;

  BanguatFx(HttpFetcher http, String url) {
    this.http = http;
    this.url = url;
  }

  Types.Fx today() throws IOException {
    String envelope =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?><soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body><TipoCambioDia xmlns=\""
            + NS
            + "\" /></soap:Body></soap:Envelope>";
    return parse(http.post(url, envelope, Map.of("Content-Type", "text/xml; charset=utf-8", "SOAPAction", "\"" + NS + "TipoCambioDia\"")));
  }

  static Types.Fx parse(String xml) throws IOException {
    try {
      var f = DocumentBuilderFactory.newInstance();
      f.setNamespaceAware(true);
      f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); // sin entidades externas
      var doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
      var rates = doc.getElementsByTagNameNS(NS, "referencia");
      var dates = doc.getElementsByTagNameNS(NS, "fecha");
      if (rates.getLength() == 0 || dates.getLength() == 0) throw new IOException("Banguat: respuesta sin tipo de cambio");
      double rate = Double.parseDouble(rates.item(0).getTextContent().trim());
      if (rate <= 0) throw new IOException("Banguat: tipo de cambio no válido");
      return new Types.Fx(rate, LocalDate.parse(dates.item(0).getTextContent().trim(), DAY), "banguat", false);
    } catch (IOException e) {
      throw e;
    } catch (Exception e) {
      throw new IOException("Banguat: no se pudo leer la respuesta", e);
    }
  }
}
