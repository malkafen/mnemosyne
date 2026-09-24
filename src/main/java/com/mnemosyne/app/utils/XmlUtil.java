package com.mnemosyne.app.utils;

import com.mnemosyne.app.exception.*;
import com.mnemosyne.app.model.DomainState;
import com.mnemosyne.app.model.DomainState.Disk;
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

  /**
   * Memory in domain XML is always KiB — libvirt normalizes every {@code <memory>} element to KiB
   * on define, so Mnemosyne writes KiB too and never has to trust the {@code unit} attribute it
   * reads back. The inventory and {@link com.mnemosyne.app.model.Server} stay in MiB; this is the
   * only place the two meet.
   */
  public static final long KIB_PER_MIB = 1024L;

  /**
   * The XML-backed part of a domain's state. Power state and autostart are not in the XML; {@link
   * DomainState#withRuntime(boolean, boolean)} fills them in from the domain handle.
   */
  public static DomainState getShortState(String domainXml) {

    try {
      Document doc = parse(domainXml);

      String name = firstText(doc, "name");
      int cpu = Integer.parseInt(firstText(doc, "vcpu").trim());
      long ram = Long.parseLong(firstText(doc, "memory").trim()) / KIB_PER_MIB;

      Element meta = firstNS(doc, MNEM_NS, "mnemosyne");
      if (meta == null) {
        return new DomainState(name, cpu, ram, null, null, null, disks(doc), false, false);
      }

      // <mnem:disks> lists the volumes Mnemosyne created; only those go when the domain does.
      Set<String> owned = new LinkedHashSet<>();
      NodeList listed = meta.getElementsByTagNameNS(MNEM_NS, "disk");
      for (int i = 0; i < listed.getLength(); i++)
        owned.add(((Element) listed.item(i)).getAttribute("path"));
      List<Disk> disks =
          disks(doc).stream().map(d -> owned.contains(d.path()) ? d.markOwned() : d).toList();

      return new DomainState(
          name,
          cpu,
          ram,
          textNS(meta, MNEM_NS, "serverId"),
          // textNS(meta, MNEM_NS, "specHash"),
          textNS(meta, MNEM_NS, "specVersion"),
          textNS(meta, MNEM_NS, "managedBy"),
          disks,
          false,
          false);
    } catch (ParserConfigurationException | SAXException | IOException e) {
      throw new XmlParseException("Failed to parse domain XML", e);
    }
  }

  /**
   * The domain's persistent-config XML with its memory size replaced.
   *
   * <p>Both {@code <memory>} (the boot-time maximum) and {@code <currentMemory>} (the balloon
   * target) are set, mirroring the pair of {@code setVcpusFlags} calls in {@code updateCpu}.
   * Leaving {@code currentMemory} behind would boot the domain with the old balloon size.
   */
  public static String withMemory(String domainXml, long ramMiB) {
    try {
      Document doc = parse(domainXml);
      setMemory(doc, "memory", ramMiB);
      // libvirt always emits currentMemory, but a hand-edited domain may not carry it.
      if (doc.getElementsByTagName("currentMemory").getLength() > 0) {
        setMemory(doc, "currentMemory", ramMiB);
      }
      return serialize(doc, "domain memory patch");
    } catch (ParserConfigurationException | SAXException | IOException e) {
      throw new XmlParseException("Failed to parse domain XML", e);
    }
  }

  /**
   * Writes {@code ramMiB} into {@code tag} as KiB, rewriting the {@code unit} attribute so the
   * value and its unit can never drift apart.
   */
  public static void setMemory(Document doc, String tag, long ramMiB) {
    Element element = (Element) doc.getElementsByTagName(tag).item(0);
    if (element == null) {
      throw new XmlParseException("Element not found: <" + tag + ">");
    }
    element.setAttribute("unit", "KiB");
    element.setTextContent(String.valueOf(ramMiB * KIB_PER_MIB));
  }

  /** Serializes a DOM tree; {@code what} only names the subject in the failure message. */
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

  /**
   * One element on its own, without the XML declaration: the shape libvirt's {@code
   * attachDeviceFlags} expects for a single device.
   */
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

  /**
   * The {@code <target><path>} of a storage pool, or null for a pool that has none — every pool
   * type that is not backed by a local directory, which cannot be reasoned about by file path.
   */
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

  /**
   * Every file-backed {@code <disk device='disk'>} of a domain, in document order.
   *
   * <p>A cdrom is skipped: it is not a disk Mnemosyne owns, and it must not consume a target letter
   * reserved for one. A network-backed disk (rbd, iscsi) has no {@code @file} and is skipped too —
   * it is still reported as a target in use, which is what keeps a hand-built domain safe.
   */
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

  /**
   * Target device names of every {@code <disk>} in the document, cdroms and network-backed disks
   * included. Attaching a disk means picking a name nothing else answers to, so this deliberately
   * looks wider than {@link #disks(Document)}.
   */
  public static Set<String> usedDiskTargets(String domainXml) {
    try {
      return usedDiskTargets(parse(domainXml));
    } catch (ParserConfigurationException | SAXException | IOException e) {
      throw new XmlParseException("Failed to parse domain XML", e);
    }
  }

  /** As {@link #usedDiskTargets(String)}, for a document already in hand. */
  public static Set<String> usedDiskTargets(Document doc) {
    Set<String> used = new LinkedHashSet<>();
    NodeList disks = doc.getElementsByTagName("disk");
    for (int i = 0; i < disks.getLength(); i++) {
      String dev = targetDev((Element) disks.item(i));
      if (dev != null) used.add(dev);
    }
    return used;
  }

  /** {@code <target dev='...'/>} of one disk element, or null when the template leaves it out. */
  private static String targetDev(Element disk) {
    Element target = (Element) disk.getElementsByTagName("target").item(0);
    if (target == null) return null;
    return clean(target.getAttribute("dev"));
  }

  /**
   * Text of a child element, scoped to {@code parent}. Scoping matters for {@code <serial>}: a
   * document-wide lookup would find the {@code <serial type='pty'>} console device instead.
   */
  private static String childText(Element parent, String tag) {
    NodeList nodes = parent.getElementsByTagName(tag);
    return nodes.getLength() == 0 ? null : clean(nodes.item(0).getTextContent());
  }

  private static List<String> diskPaths(Document doc) {
    return disks(doc).stream().map(Disk::path).toList();
  }
}
