package com.mnemosyne.app.model;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Read-only inspector for an existing libvirt domain XML description.
 *
 * <p>Counterpart to {@link Server}'s XML builders: where {@code Server} turns desired state into
 * XML, this turns the actual XML reported by libvirt back into facts. Pure and stateless — holds no
 * libvirt connection — so it can be unit-tested against a static XML sample.
 */
final class DomainInspector {

  private DomainInspector() {}

  static final String MNEM_NS = "https://mnemosyne.dev/schema/v1";

  /**
   * Collects the backing file paths of a domain's writable disks.
   *
   * <p>Only elements with {@code device="disk"} are considered; cdrom devices (e.g. the cloud-init
   * nocloud ISO) and non-file sources (block/network/volume) are skipped, since reconcile deletes
   * file-backed storage volumes by path.
   *
   * @param domainXml the XML returned by {@code Domain.getXMLDesc(0)}
   * @return disk source file paths, in document order (never {@code null})
   */
  static List<String> diskPaths(String domainXml)
      throws ParserConfigurationException, SAXException, IOException {
    Document doc = parse(domainXml);
    List<String> paths = new ArrayList<>();

    NodeList disks = doc.getElementsByTagName("disk");
    for (int i = 0; i < disks.getLength(); i++) {
      Element disk = (Element) disks.item(i);
      if (!"disk".equals(disk.getAttribute("device"))) {
        continue;
      }
      Element source = (Element) disk.getElementsByTagName("source").item(0);
      if (source == null) {
        continue;
      }
      String file = source.getAttribute("file");
      if (!file.isBlank()) {
        paths.add(file);
      }
    }
    return paths;
  }

  static DomainState readState(String domainXml)
      throws ParserConfigurationException, SAXException, IOException {
    Document doc = parse(domainXml);

    String name = firstText(doc, "name");
    int cpu = Integer.parseInt(firstText(doc, "vcpu").trim());
    long ram = Long.parseLong(firstText(doc, "memory").trim()) / 1024;

    Element meta = firstNS(doc, MNEM_NS, "mnemosyne");
    if (meta == null) {
      //return new DomainState(name, 0, 0, null, null, null, null);
      return new DomainState(name, 0, 0, null, null, null);
    }

    return new DomainState(
        name,
        cpu,
        ram,
        textNS(meta, MNEM_NS, "serverId"),
        //textNS(meta, MNEM_NS, "specHash"),
        textNS(meta, MNEM_NS, "specVersion"),
        textNS(meta, MNEM_NS, "managedBy"));
  }

  private static Element firstNS(Document doc, String ns, String localName) {
    NodeList nodes = doc.getElementsByTagNameNS(ns, localName);
    return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
  }

  private static String firstText(Document doc, String tag) {
    NodeList nodes = doc.getElementsByTagName(tag);
    return nodes.getLength() == 0 ? null : clean(nodes.item(0).getTextContent());
  }

  private static String textNS(Element scope, String ns, String localName) {
    NodeList nodes = scope.getElementsByTagNameNS(ns, localName);
    return nodes.getLength() == 0 ? null : clean(nodes.item(0).getTextContent());
  }

  private static String clean(String s) {
    return (s == null || s.isBlank()) ? null : s.trim();
  }

  private static Document parse(String xml)
      throws ParserConfigurationException, SAXException, IOException {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    // Harden against XXE: domain XML is trusted, but disabling DTDs is cheap and correct.
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    factory.setExpandEntityReferences(false);
    factory.setNamespaceAware(true);

    DocumentBuilder builder = factory.newDocumentBuilder();
    return builder.parse(new InputSource(new StringReader(xml)));
  }
}
