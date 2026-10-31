package com.mnemosyne.app.utils;

import com.mnemosyne.app.exception.*;
import com.mnemosyne.app.model.DomainState;
import com.mnemosyne.app.model.DomainState.Disk;
import com.mnemosyne.app.model.GuestNetwork;
import com.mnemosyne.app.model.InitMarker;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

public class XmlUtil {

  private static Document parse(String xml)
      throws ParserConfigurationException, SAXException, IOException {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
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

  public static final long KIB_PER_MIB = 1024L;

  public static DomainState getShortState(String domainXml) {

    try {
      Document doc = parse(domainXml);

      String name = firstText(doc, "name");
      int cpu = Integer.parseInt(firstText(doc, "vcpu").trim());
      long ram = Long.parseLong(firstText(doc, "memory").trim()) / KIB_PER_MIB;

      Element meta = firstNS(doc, MNEM_NS, "mnemosyne");
      if (meta == null) {
        return new DomainState(
            name,
            cpu,
            ram,
            null,
            null,
            null,
            disks(doc),
            false,
            false,
            List.of(),
            List.of(),
            null,
            interfaceNetwork(doc),
            null,
            guestAgent(doc));
      }

      Set<String> owned = paths(meta, "disk");
      Set<String> reused = paths(meta, "volume");
      owned.removeAll(reused);
      List<Disk> disks =
          disks(doc).stream().map(d -> owned.contains(d.path()) ? d.markOwned() : d).toList();
      List<String> attached = disks.stream().map(Disk::path).toList();
      List<String> detached = owned.stream().filter(p -> !attached.contains(p)).toList();

      return new DomainState(
          name,
          cpu,
          ram,
          textNS(meta, MNEM_NS, "serverId"),
          textNS(meta, MNEM_NS, "specVersion"),
          textNS(meta, MNEM_NS, "managedBy"),
          disks,
          false,
          false,
          detached,
          List.copyOf(reused),
          init(meta),
          interfaceNetwork(doc),
          guestNetwork(meta),
          guestAgent(doc));
    } catch (ParserConfigurationException | SAXException | IOException e) {
      throw new XmlParseException("Failed to parse domain XML", e);
    }
  }

  private static InitMarker init(Element meta) {
    NodeList nodes = meta.getElementsByTagNameNS(MNEM_NS, "init");
    if (nodes.getLength() == 0) return null;
    Element e = (Element) nodes.item(0);
    return new InitMarker(
        attr(e, "state"), attr(e, "token"), attr(e, "created"), attr(e, "finished"));
  }

  private static GuestNetwork guestNetwork(Element meta) {
    NodeList nodes = meta.getElementsByTagNameNS(MNEM_NS, "network");
    if (nodes.getLength() == 0) return null;
    Element e = (Element) nodes.item(0);
    String ip = clean(e.getAttribute("ip"));
    return ip == null ? null : new GuestNetwork(ip, clean(e.getAttribute("gateway")));
  }

  private static List<Element> devices(Document doc, String tag) {
    List<Element> found = new ArrayList<>();
    NodeList devices = doc.getDocumentElement().getElementsByTagName("devices");
    for (int d = 0; d < devices.getLength(); d++) {
      Element parent = (Element) devices.item(d);
      if (parent.getParentNode() != doc.getDocumentElement()) continue;
      NodeList children = parent.getChildNodes();
      for (int i = 0; i < children.getLength(); i++)
        if (children.item(i) instanceof Element e && tag.equals(e.getTagName())) found.add(e);
    }
    return found;
  }

  private static Element firstInterface(Document doc) {
    List<Element> interfaces = devices(doc, "interface");
    return interfaces.isEmpty() ? null : interfaces.get(0);
  }

  private static boolean guestAgent(Document doc) {
    for (Element channel : devices(doc, "channel")) {
      NodeList targets = channel.getElementsByTagName("target");
      for (int i = 0; i < targets.getLength(); i++)
        if (GUEST_AGENT_CHANNEL.equals(((Element) targets.item(i)).getAttribute("name")))
          return true;
    }
    return false;
  }

  public static final String GUEST_AGENT_CHANNEL = "org.qemu.guest_agent.0";

  private static String interfaceNetwork(Document doc) {
    Element iface = firstInterface(doc);
    if (iface == null || !"network".equals(iface.getAttribute("type"))) return null;
    Element source = (Element) iface.getElementsByTagName("source").item(0);
    return source == null ? null : clean(source.getAttribute("network"));
  }

  public static String interfaceWithNetwork(String domainXml, String network) {
    try {
      Document doc = parse(domainXml);
      Element iface = firstInterface(doc);
      if (iface == null || !"network".equals(iface.getAttribute("type")))
        throw new XmlParseException("Domain has no <interface type='network'> to rewire");
      Element source = (Element) iface.getElementsByTagName("source").item(0);
      if (source == null) throw new XmlParseException("Domain interface has no <source>");
      while (source.getAttributes().getLength() > 0)
        source.removeAttributeNode((Attr) source.getAttributes().item(0));
      source.setAttribute("network", network);
      return serializeFragment(iface, "interface");
    } catch (ParserConfigurationException | SAXException | IOException e) {
      throw new XmlParseException("Failed to parse domain XML", e);
    }
  }

  private static String attr(Element e, String name) {
    return e.hasAttribute(name) ? e.getAttribute(name) : null;
  }

  private static Set<String> paths(Element meta, String tag) {
    Set<String> paths = new LinkedHashSet<>();
    NodeList listed = meta.getElementsByTagNameNS(MNEM_NS, tag);
    for (int i = 0; i < listed.getLength(); i++)
      paths.add(((Element) listed.item(i)).getAttribute("path"));
    return paths;
  }

  public static String withMemory(String domainXml, long ramMiB) {
    try {
      Document doc = parse(domainXml);
      setMemory(doc, "memory", ramMiB);
      if (doc.getElementsByTagName("currentMemory").getLength() > 0) {
        setMemory(doc, "currentMemory", ramMiB);
      }
      return serialize(doc, "domain memory patch");
    } catch (ParserConfigurationException | SAXException | IOException e) {
      throw new XmlParseException("Failed to parse domain XML", e);
    }
  }

  public static void setMemory(Document doc, String tag, long ramMiB) {
    Element element = (Element) doc.getElementsByTagName(tag).item(0);
    if (element == null) {
      throw new XmlParseException("Element not found: <" + tag + ">");
    }
    element.setAttribute("unit", "KiB");
    element.setTextContent(String.valueOf(ramMiB * KIB_PER_MIB));
  }

  public static String serialize(Document doc, String what) {
    try {
      Transformer transformer = TransformerFactory.newInstance().newTransformer();
      transformer.setOutputProperty(OutputKeys.INDENT, "yes");
      StringWriter writer = new StringWriter();
      transformer.transform(new DOMSource(doc), new StreamResult(writer));
      return writer.toString();
    } catch (TransformerException e) {
      throw new XmlParseException("Failed to serialize XML for '" + what + "'", e);
    }
  }

  public static String serializeFragment(Element element, String what) {
    try {
      Transformer transformer = TransformerFactory.newInstance().newTransformer();
      transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
      transformer.setOutputProperty(OutputKeys.INDENT, "yes");
      StringWriter writer = new StringWriter();
      transformer.transform(new DOMSource(element), new StreamResult(writer));
      return writer.toString();
    } catch (TransformerException e) {
      throw new XmlParseException("Failed to serialize XML for '" + what + "'", e);
    }
  }

  public static String poolTargetPath(String poolXml) {
    try {
      Document doc = parse(poolXml);
      NodeList targets = doc.getElementsByTagName("target");
      if (targets.getLength() == 0) return null;
      return childText((Element) targets.item(0), "path");
    } catch (ParserConfigurationException | SAXException | IOException e) {
      throw new XmlParseException("Failed to parse storage pool XML", e);
    }
  }

  public static List<String> diskPaths(String domainXml) {
    try {
      Document doc = parse(domainXml);
      return diskPaths(doc);
    } catch (ParserConfigurationException | SAXException | IOException e) {
      throw new XmlParseException("Failed to parse domain XML", e);
    }
  }

  public static List<Disk> disks(Document doc) {
    List<Disk> found = new ArrayList<>();
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
      if (file.isBlank()) {
        continue;
      }
      found.add(new Disk(targetDev(disk), file, childText(disk, "serial")));
    }
    return found;
  }

  public static Set<String> usedDiskTargets(String domainXml) {
    try {
      return usedDiskTargets(parse(domainXml));
    } catch (ParserConfigurationException | SAXException | IOException e) {
      throw new XmlParseException("Failed to parse domain XML", e);
    }
  }

  public static Set<String> usedDiskTargets(Document doc) {
    Set<String> used = new LinkedHashSet<>();
    NodeList disks = doc.getElementsByTagName("disk");
    for (int i = 0; i < disks.getLength(); i++) {
      String dev = targetDev((Element) disks.item(i));
      if (dev != null) used.add(dev);
    }
    return used;
  }

  private static String targetDev(Element disk) {
    Element target = (Element) disk.getElementsByTagName("target").item(0);
    if (target == null) return null;
    return clean(target.getAttribute("dev"));
  }

  private static String childText(Element parent, String tag) {
    NodeList nodes = parent.getElementsByTagName(tag);
    return nodes.getLength() == 0 ? null : clean(nodes.item(0).getTextContent());
  }

  private static List<String> diskPaths(Document doc) {
    return disks(doc).stream().map(Disk::path).toList();
  }
}
