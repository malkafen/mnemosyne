package com.mnemosyne.app.utils;

import com.mnemosyne.app.exception.*;
import com.mnemosyne.app.model.DomainState;
import com.mnemosyne.app.model.DomainState.Disk;
import com.mnemosyne.app.model.DomainState.Nic;
import com.mnemosyne.app.model.InitMarker;
import com.mnemosyne.app.model.NetState;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

public class XmlUtil {

  static Document parse(String xml) throws ParserConfigurationException, SAXException, IOException {
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
            nics(doc));
      }

      // <mnem:disks> lists the volumes Mnemosyne created; only those go when the domain does.
      Set<String> owned = paths(meta, "disk");
      // <mnem:reused-disks> lists the volumes it found in the pool and attached. A path on both
      // lists is taken as reused: of the two mistakes, keeping a volume is the one that can be
      // undone.
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
          // textNS(meta, MNEM_NS, "specHash"),
          textNS(meta, MNEM_NS, "specVersion"),
          textNS(meta, MNEM_NS, "managedBy"),
          disks,
          false,
          false,
          detached,
          List.copyOf(reused),
          init(meta),
          nics(doc));
    } catch (ParserConfigurationException | SAXException | IOException e) {
      throw new XmlParseException("Failed to parse domain XML", e);
    }
  }

  /**
   * The {@code <mnem:init>} marker as the metadata holds it, or null when there is none. Nothing is
   * validated here: an unknown state is the plan's to refuse, and it can only name it if it gets
   * it.
   */
  private static InitMarker init(Element meta) {
    NodeList nodes = meta.getElementsByTagNameNS(MNEM_NS, "init");
    if (nodes.getLength() == 0) return null;
    Element e = (Element) nodes.item(0);
    return new InitMarker(
        attr(e, "state"), attr(e, "token"), attr(e, "created"), attr(e, "finished"));
  }

  /** An attribute's value, or null when it is absent — DOM itself answers with an empty string. */
  private static String attr(Element e, String name) {
    return e.hasAttribute(name) ? e.getAttribute(name) : null;
  }

  /** The {@code path} attributes of every {@code <mnem:tag>} under the metadata element. */
  private static Set<String> paths(Element meta, String tag) {
    Set<String> paths = new LinkedHashSet<>();
    NodeList listed = meta.getElementsByTagNameNS(MNEM_NS, tag);
    for (int i = 0; i < listed.getLength(); i++)
      paths.add(((Element) listed.item(i)).getAttribute("path"));
    return paths;
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

  /** Escapes text for an XML attribute or element written by hand. */
  public static String escape(String s) {
    if (s == null) return "";
    return s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("'", "&apos;")
        .replace("\"", "&quot;");
  }

  /**
   * Every {@code <interface>} of a domain directly under {@code <devices>}, in document order: the
   * libvirt network it is attached to (null for a bridge or direct interface) and its {@code
   * <filterref>}, with parameters.
   */
  public static List<Nic> nics(Document doc) {
    List<Nic> found = new ArrayList<>();
    NodeList ifaces = doc.getElementsByTagName("interface");
    for (int i = 0; i < ifaces.getLength(); i++) {
      Element iface = (Element) ifaces.item(i);
      if (iface.getParentNode() == null || !"devices".equals(iface.getParentNode().getNodeName()))
        continue;
      Element source = child(iface, "source");
      String network = source == null ? null : clean(source.getAttribute("network"));
      if (!"network".equals(iface.getAttribute("type"))) network = null;
      Element ref = child(iface, "filterref");
      String filter = ref == null ? null : clean(ref.getAttribute("filter"));
      Map<String, String> params = new TreeMap<>();
      if (ref != null) {
        for (Element p : children(ref, "parameter"))
          params.put(p.getAttribute("name"), p.getAttribute("value"));
      }
      Element macEl = child(iface, "mac");
      String mac = macEl == null ? null : clean(macEl.getAttribute("address"));
      found.add(new Nic(network, filter, Map.copyOf(params), mac));
    }
    return List.copyOf(found);
  }

  /**
   * The first {@code <interface>} of a domain's XML with its {@code <filterref>} replaced by {@code
   * filter} and {@code params}, or removed when {@code filter} is null: the device XML {@code
   * virDomainUpdateDeviceFlags} takes. The rest of the interface — its MAC above all, which is how
   * libvirt finds the device to update — is passed through as the host reported it.
   */
  public static String interfaceWithFilter(
      String domainXml, String filter, Map<String, String> params) {
    try {
      Document doc = parse(domainXml);
      Element iface = null;
      NodeList ifaces = doc.getElementsByTagName("interface");
      for (int i = 0; i < ifaces.getLength() && iface == null; i++) {
        Element e = (Element) ifaces.item(i);
        if (e.getParentNode() != null && "devices".equals(e.getParentNode().getNodeName()))
          iface = e;
      }
      if (iface == null) throw new XmlParseException("Domain has no <interface> to filter");
      for (Element old : children(iface, "filterref")) iface.removeChild(old);
      if (filter != null) {
        Element ref = doc.createElement("filterref");
        ref.setAttribute("filter", filter);
        new TreeMap<>(params)
            .forEach(
                (k, v) -> {
                  Element p = doc.createElement("parameter");
                  p.setAttribute("name", k);
                  p.setAttribute("value", v);
                  ref.appendChild(p);
                });
        iface.appendChild(ref);
      }
      return serializeFragment(iface, "interface filter");
    } catch (ParserConfigurationException | SAXException | IOException e) {
      throw new XmlParseException("Failed to parse domain XML", e);
    }
  }

  /** Whether the network's metadata carries {@code <mnem:managedBy>mnemosyne</mnem:managedBy>}. */
  public static boolean networkManaged(String networkXml) {
    try {
      Document doc = parse(networkXml);
      Element meta = firstNS(doc, MNEM_NS, "mnemosyne");
      return meta != null && "mnemosyne".equals(textNS(meta, MNEM_NS, "managedBy"));
    } catch (ParserConfigurationException | SAXException | IOException e) {
      throw new XmlParseException("Failed to parse network XML", e);
    }
  }

  /**
   * The parts of a network's XML the inventory can describe. A {@code <forward>} without a mode is
   * libvirt's NAT; no {@code <forward>} at all is an isolated network. Only the first IPv4 {@code
   * <ip>} and the first DHCP range are read.
   */
  public static NetState.Shape networkShape(String networkXml) {
    try {
      Document doc = parse(networkXml);
      Element root = doc.getDocumentElement();
      Element forward = child(root, "forward");
      String mode;
      if (forward == null) mode = "isolated";
      else {
        String m = clean(forward.getAttribute("mode"));
        mode = m == null ? "nat" : m;
      }
      Element bridgeEl = child(root, "bridge");
      String bridge = bridgeEl == null ? null : clean(bridgeEl.getAttribute("name"));

      String address = null;
      String dhcp = null;
      for (Element ip : children(root, "ip")) {
        String family = clean(ip.getAttribute("family"));
        if (family != null && !"ipv4".equals(family)) continue;
        String addr = clean(ip.getAttribute("address"));
        if (addr == null) continue;
        String prefix = clean(ip.getAttribute("prefix"));
        String netmask = clean(ip.getAttribute("netmask"));
        int p =
            prefix != null
                ? Integer.parseInt(prefix)
                : netmask != null ? Ipv4.prefixOf(netmask) : 32;
        address = addr + "/" + p;
        Element dhcpEl = child(ip, "dhcp");
        Element range = dhcpEl == null ? null : child(dhcpEl, "range");
        if (range != null) dhcp = range.getAttribute("start") + "-" + range.getAttribute("end");
        break;
      }
      return new NetState.Shape(mode, address, bridge, dhcp);
    } catch (ParserConfigurationException | SAXException | IOException e) {
      throw new XmlParseException("Failed to parse network XML", e);
    }
  }

  /**
   * The DHCP host reservations of a network's first IPv4 {@code <ip>}, each as its own {@code <host
   * .../>} element, ready to be written back into a redefined network.
   */
  public static List<String> dhcpHosts(String networkXml) {
    try {
      Element root = parse(networkXml).getDocumentElement();
      for (Element ip : children(root, "ip")) {
        String family = clean(ip.getAttribute("family"));
        if (family != null && !"ipv4".equals(family)) continue;
        Element dhcp = child(ip, "dhcp");
        if (dhcp == null) return List.of();
        List<String> hosts = new ArrayList<>();
        for (Element h : children(dhcp, "host"))
          hosts.add(serializeFragment(h, "dhcp host").trim());
        return List.copyOf(hosts);
      }
      return List.of();
    } catch (ParserConfigurationException | SAXException | IOException e) {
      throw new XmlParseException("Failed to parse network XML", e);
    }
  }

  /** The {@code <uuid>} of a network or filter XML, or null. */
  public static String uuid(String xml) {
    try {
      Element u = child(parse(xml).getDocumentElement(), "uuid");
      return u == null ? null : clean(u.getTextContent());
    } catch (ParserConfigurationException | SAXException | IOException e) {
      throw new XmlParseException("Failed to parse XML", e);
    }
  }

  /**
   * A network filter reduced to what decides its behaviour, for comparing the host's copy with the
   * inventory's: the chain, and every filterref and rule with its attributes sorted. The uuid and
   * the order libvirt prints things in are left out; rules carry their priority, which is what
   * orders them.
   */
  public static List<String> canonicalFilter(String filterXml) {
    try {
      Element root = parse(filterXml).getDocumentElement();
      List<String> lines = new ArrayList<>();
      String chain = clean(root.getAttribute("chain"));
      lines.add("chain=" + (chain == null ? "root" : chain));
      for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
        if (!(n instanceof Element e)) continue;
        switch (e.getTagName()) {
          case "filterref" -> {
            StringBuilder sb = new StringBuilder("ref " + e.getAttribute("filter"));
            Map<String, String> params = new TreeMap<>();
            for (Element p : children(e, "parameter"))
              params.put(p.getAttribute("name"), p.getAttribute("value"));
            params.forEach((k, v) -> sb.append(' ').append(k).append('=').append(v));
            lines.add(sb.toString());
          }
          case "rule" -> {
            StringBuilder sb =
                new StringBuilder(
                    String.format(
                        "rule %s %s %s",
                        e.getAttribute("action"),
                        e.getAttribute("direction"),
                        e.hasAttribute("priority") ? e.getAttribute("priority") : "500"));
            for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
              if (!(c instanceof Element proto)) continue;
              sb.append(' ').append(proto.getTagName());
              sb.append(sortedAttributes(proto));
            }
            lines.add(sb.toString());
          }
          default -> {
            // uuid and anything else libvirt adds carry no behaviour
          }
        }
      }
      List<String> sorted = new ArrayList<>(lines);
      java.util.Collections.sort(sorted);
      return List.copyOf(sorted);
    } catch (ParserConfigurationException | SAXException | IOException e) {
      throw new XmlParseException("Failed to parse network filter XML", e);
    }
  }

  private static String sortedAttributes(Element e) {
    Map<String, String> attrs = new TreeMap<>();
    var map = e.getAttributes();
    for (int i = 0; i < map.getLength(); i++) {
      Node a = map.item(i);
      String value = a.getNodeValue();
      // libvirt prints a state list as it was given; the order of its words means nothing
      if ("state".equals(a.getNodeName())) {
        String[] words = value.split(",");
        java.util.Arrays.sort(words);
        value = String.join(",", words);
      }
      attrs.put(a.getNodeName(), value);
    }
    StringBuilder sb = new StringBuilder();
    attrs.forEach((k, v) -> sb.append(' ').append(k).append('=').append(v));
    return sb.toString();
  }

  /** The first direct child element named {@code tag}, or null. */
  private static Element child(Element parent, String tag) {
    for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling())
      if (n instanceof Element e && tag.equals(e.getTagName())) return e;
    return null;
  }

  /** Every direct child element named {@code tag}. */
  private static List<Element> children(Element parent, String tag) {
    List<Element> found = new ArrayList<>();
    for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling())
      if (n instanceof Element e && tag.equals(e.getTagName())) found.add(e);
    return found;
  }
}
