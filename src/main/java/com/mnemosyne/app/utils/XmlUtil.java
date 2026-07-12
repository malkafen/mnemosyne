package com.mnemosyne.app.utils;

import com.mnemosyne.app.exception.*;
import com.mnemosyne.app.model.DomainState;
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

public class XmlUtil {

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

  public static final String MNEM_NS = "https://mnemosyne.dev/schema/v1";

  public static DomainState getShortState(String domainXml) {

    try {
      Document doc = parse(domainXml);

      String name = firstText(doc, "name");
      int cpu = Integer.parseInt(firstText(doc, "vcpu").trim());
      long ram = Long.parseLong(firstText(doc, "memory").trim()) / 1024;

      Element meta = firstNS(doc, MNEM_NS, "mnemosyne");
      if (meta == null) {
        return new DomainState(name, cpu, ram, null, null, null);
      }

      return new DomainState(
          name,
          cpu,
          ram,
          textNS(meta, MNEM_NS, "serverId"),
          // textNS(meta, MNEM_NS, "specHash"),
          textNS(meta, MNEM_NS, "specVersion"),
          textNS(meta, MNEM_NS, "managedBy"));
    } catch (ParserConfigurationException | SAXException | IOException e) {
      throw new XmlParseException("Failed to parse domain XML", e);
    }
  }

  public static List<String> diskPaths(String domainXml) {
    try {
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
    } catch (ParserConfigurationException | SAXException | IOException e) {
      throw new XmlParseException("Failed to parse domain XML", e);
    }
  }
}
