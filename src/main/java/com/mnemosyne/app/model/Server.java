package com.mnemosyne.app.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.mnemosyne.app.exception.TemplateException;
import com.mnemosyne.app.exception.XmlParseException;
import com.mnemosyne.app.utils.*;
import com.mnemosyne.app.utils.TargetDev;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import lombok.Getter;
import lombok.Setter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

@Setter
@Getter
public class Server {

  private static final String IPV4_PATTERN =
      "^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$";
  private static final String IPV4_CIDR_PATTERN =
      "^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)/(3[0-2]|[12]?\\d)$";

  private String name;

  @NotBlank(message = "Server id is required")
  private String id;

  private String specHash;

  @Min(value = 1, message = "CPU must be at least 1")
  @Max(value = 128, message = "CPU must not exceed 128")
  private int cpu;

  @Min(value = 256, message = "RAM must be at least 256 MiB")
  @Max(value = 1048576, message = "RAM must not exceed 1048576 MiB (1 TiB)")
  private long ram;

  @NotBlank(message = "IP address is required")
  @Pattern(
      regexp = IPV4_CIDR_PATTERN,
      message = "IP must be in CIDR notation (e.g. 192.168.70.70/24)")
  private String ip;

  @Pattern(
      regexp = IPV4_PATTERN,
      message = "Gateway must be a valid IPv4 address (e.g. 192.168.70.1)")
  private String gateway;

  @Positive(message = "Disk size must be greater than 0")
  @Min(value = 10, message = "Disk size must be at least 10 GiB")
  private int disk = 30;

  @NotBlank(message = "Storage pool name is required")
  private String pool = "default";

  /**
   * Additional blank disks, attached after the root one in the order listed here. Optional: a
   * server without the key gets exactly the disk it has always had.
   *
   * <p>The cap is far below the 25 target names a {@code vd} bus offers, on purpose: it leaves room
   * for a disk or a cdrom somebody attached by hand without Mnemosyne running out of letters.
   */
  @Valid
  @Size(max = 24, message = "At most 24 extra disks per server")
  private List<ExtraDisk> extraDisks = List.of();

  /**
   * Paths of the provisioned extra volumes, keyed by disk name. Filled in by the reconciler once
   * the volumes exist, the way {@link #volPath} is for the root disk: the domain XML cannot be
   * built before the disks it points at have a path on the host.
   */
  private Map<String, String> extraVolPaths = new LinkedHashMap<>();

  private Templates templates;

  private String volLookup;

  @NotBlank(message = "Cloud-init metadata base URL is required")
  @Pattern(regexp = "^https?://.+", message = "metaUrl must be a valid HTTP/HTTPS URL")
  private String metaUrl;

  private String volPath = null;

  @NotBlank(message = "Libvirt network name is required")
  private String network = "default";

  /** Whether the VM should be running; reconciled on every run, not only at creation. */
  private boolean launch = true;

  /** Libvirt autostart; {@code null} leaves whatever the domain already has. */
  private Boolean autostart;

  /** An {@code extraDisks:} key present but empty deserializes to null; an absent list is fine. */
  public void setExtraDisks(List<ExtraDisk> extraDisks) {
    this.extraDisks = extraDisks == null ? List.of() : extraDisks;
  }

  /**
   * Two disks with one name would fight over the same volume file and the same {@code <serial>},
   * and the second would silently win. Bean Validation cannot express it per field, so it is
   * asserted across the list.
   */
  @AssertTrue(message = "extraDisks names must be unique within a server")
  public boolean isExtraDiskNamesUnique() {
    List<String> names =
        extraDisks.stream().map(ExtraDisk::getName).filter(Objects::nonNull).toList();
    return names.size() == Set.copyOf(names).size();
  }

  /** Volume names the extra disks of this server occupy in their pools. */
  public List<String> extraVolNames() {
    return extraDisks.stream().map(d -> d.volName(getName())).toList();
  }

  public record Seed(String name, String metaData, String userData, String networkConfig) {}

  public Seed buildSeed() {
    return new Seed(getName(), buildMetaData(), buildUserDataYaml(), buildNetworkConfigYaml());
  }

  private String seedUrl() {
    String base = metaUrl.endsWith("/") ? metaUrl : metaUrl + "/";
    return base + getName() + "/";
  }

  public static String specHash(int cpu, long ram) {
    String spec = String.join("\u001f", String.valueOf(cpu), String.valueOf(ram));
    return Sha256Util.sha256Hex(spec);
  }

  public String getName() {
    return (name == null || name.isBlank()) ? id : name;
  }

  /**
   * The disk image is named after the VM plus the extension of the format the volume template
   * creates, so a file-backed pool holds `<vm>.qcow2` next to the base images it was cloned from.
   */
  public String getVolName() {
    return getName() + ".qcow2";
  }

  public String getSpecHash() {
    return specHash(this.cpu, this.ram);
  }

  // XML builders

  public String buildVolumeXml() {
    String tmpl = this.templates.getVolTmpl();
    Document doc = loadXmlTemplate(tmpl);
    setElementText(doc, tmpl, "name", getVolName());
    setElementText(doc, tmpl, "capacity", String.valueOf(this.disk));
    return documentToString(doc);
  }

  /**
   * Volume XML for one extra disk, from the same template as the root disk.
   *
   * <p>The template needs nothing added for this: it already describes a sparse qcow2 of a given
   * capacity. The difference is in how the volume is created, not in what it looks like — an extra
   * disk is created empty instead of being cloned from the base image and resized.
   */
  public String buildExtraVolumeXml(ExtraDisk disk) {
    String tmpl = this.templates.getVolTmpl();
    Document doc = loadXmlTemplate(tmpl);
    setElementText(doc, tmpl, "name", disk.volName(getName()));
    setElementText(doc, tmpl, "capacity", String.valueOf(disk.getSize()));
    return documentToString(doc);
  }

  /**
   * The {@code <disk>} element for one extra disk, on its own, ready for libvirt's {@code
   * attachDeviceFlags}. Used when the disk is added to a domain that already exists; at creation
   * the same element goes straight into the domain XML.
   */
  public String buildExtraDiskXml(ExtraDisk disk, String targetDev, String path) {
    Document doc = loadXmlTemplate(this.templates.getServerTmpl());
    Element element = extraDiskElement(doc, rootDisk(doc), disk, targetDev, path);
    return XmlUtil.serializeFragment(element, getName());
  }

  public String buildServerXml() {
    String tmpl = this.templates.getServerTmpl();
    Document doc = loadXmlTemplate(tmpl);
    setElementText(doc, tmpl, "name", getName());
    XmlUtil.setMemory(doc, "memory", this.ram);
    setElementText(doc, tmpl, "vcpu", String.valueOf(this.cpu));
    setElementTextNS(doc, "https://mnemosyne.dev/schema/v1", "serverId", getId());
    setCloudInitSerial(doc);
    setDiskSource(doc);
    appendExtraDisks(doc);
    setInterfaceNetwork(doc);
    return documentToString(doc);
  }

  public String buildMnemosyneMetadataXml() {
    return String.format(
        "<mnemosyne>"
            + "<managedBy>mnemosyne</managedBy>"
            + "<serverId>%s</serverId>"
            + "<specVersion>1</specVersion>"
            + "</mnemosyne>",
        getId());
  }

  private void setElementTextNS(Document doc, String namespaceUri, String localName, String value) {
    NodeList nodes = doc.getElementsByTagNameNS(namespaceUri, localName);
    if (nodes.getLength() == 0) {
      throw new TemplateException(
          missing(this.templates.getServerTmpl(), "<{" + namespaceUri + "}" + localName + ">"));
    }
    nodes.item(0).setTextContent(value);
  }

  /**
   * A template that cannot be filled in is a configuration error. Reporting it by name beats
   * failing later with an NPE, or defining a domain whose disk source was silently left empty.
   */
  private String missing(String tmpl, String what) {
    return "Template '" + tmpl + "' has no " + what + " to fill in for server '" + getName() + "'";
  }

  // XML helpers

  private Document loadXmlTemplate(String path) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      // The reference templates are heavily commented. Neither the comments nor the blank lines
      // they sit in belong in the XML handed to libvirt, which discards them anyway but echoes
      // the whole document in trace logs.
      factory.setIgnoringComments(true);
      DocumentBuilder builder = factory.newDocumentBuilder();
      Document doc = builder.parse(new File(path));
      stripBlankText(doc.getDocumentElement());
      return doc;
    } catch (ParserConfigurationException | SAXException | IOException e) {
      throw new XmlParseException("Failed to load XML template: " + path, e);
    }
  }

  /**
   * Drops whitespace-only text nodes. Without this the serializer indents on top of the template's
   * own formatting and the result grows a ragged left margin with every nesting level.
   */
  private static void stripBlankText(Node node) {
    NodeList children = node.getChildNodes();
    for (int i = children.getLength() - 1; i >= 0; i--) {
      Node child = children.item(i);
      if (child.getNodeType() == Node.TEXT_NODE && child.getTextContent().isBlank()) {
        node.removeChild(child);
      } else if (child.getNodeType() == Node.ELEMENT_NODE) {
        stripBlankText(child);
      }
    }
  }

  private String documentToString(Document doc) {
    return XmlUtil.serialize(doc, getName());
  }

  private void setElementText(Document doc, String tmpl, String tag, String value) {
    Element element = (Element) doc.getElementsByTagName(tag).item(0);
    if (element == null) {
      throw new TemplateException(missing(tmpl, "<" + tag + ">"));
    }
    element.setTextContent(value);
  }

  private void setCloudInitSerial(Document doc) {
    NodeList entries = doc.getElementsByTagName("entry");
    for (int i = 0; i < entries.getLength(); i++) {
      Element entry = (Element) entries.item(i);
      if ("serial".equals(entry.getAttribute("name"))) {
        entry.setTextContent("ds=nocloud;s=" + seedUrl());
        return;
      }
    }
    throw new TemplateException(
        missing(this.templates.getServerTmpl(), "<sysinfo> <entry name='serial'>"));
  }

  private void setDiskSource(Document doc) {
    setSourceFile(rootDisk(doc), this.volPath);
  }

  /** The template's root disk: the first {@code device='disk'}, so never the cdrom. */
  private Element rootDisk(Document doc) {
    NodeList disks = doc.getElementsByTagName("disk");
    for (int i = 0; i < disks.getLength(); i++) {
      Element disk = (Element) disks.item(i);
      if ("disk".equals(disk.getAttribute("device"))) return disk;
    }
    throw new TemplateException(missing(this.templates.getServerTmpl(), "<disk device='disk'>"));
  }

  private void setSourceFile(Element disk, String path) {
    Element source = (Element) disk.getElementsByTagName("source").item(0);
    if (source == null) {
      throw new TemplateException(
          missing(this.templates.getServerTmpl(), "<source> inside <disk device='disk'>"));
    }
    source.setAttribute("file", path);
  }

  /**
   * Adds the extra disks to the domain being defined, in inventory order, next to the root disk.
   *
   * <p>Target names are taken after everything the template already uses, so a template carrying a
   * cdrom or a second disk of its own cannot end up with two devices on one name.
   */
  private void appendExtraDisks(Document doc) {
    if (this.extraDisks.isEmpty()) return;

    Element root = rootDisk(doc);
    Element devices = (Element) root.getParentNode();
    Set<String> used = XmlUtil.usedDiskTargets(doc);
    List<String> targets =
        TargetDev.allocate(TargetDev.prefix(targetDevOf(root)), used, this.extraDisks.size());

    if (targets.size() < this.extraDisks.size()) {
      throw new TemplateException(
          "Template '"
              + this.templates.getServerTmpl()
              + "' leaves no free target device name for all "
              + this.extraDisks.size()
              + " extra disks of server '"
              + getName()
              + "'");
    }
    for (int i = 0; i < this.extraDisks.size(); i++) {
      ExtraDisk disk = this.extraDisks.get(i);
      devices.appendChild(extraDiskElement(doc, root, disk, targets.get(i), pathOf(disk)));
    }
  }

  private String pathOf(ExtraDisk disk) {
    String path = this.extraVolPaths.get(disk.getName());
    if (path == null || path.isBlank()) {
      throw new TemplateException(
          "No provisioned volume for extra disk '"
              + disk.getName()
              + "' of server '"
              + getName()
              + "'");
    }
    return path;
  }

  private static String targetDevOf(Element disk) {
    Element target = (Element) disk.getElementsByTagName("target").item(0);
    return target == null ? null : target.getAttribute("dev");
  }

  /**
   * One extra disk, built by cloning the template's root disk.
   *
   * <p>Cloning rather than generating is deliberate: whatever the operator tuned on the root disk —
   * {@code bus}, {@code discard}, {@code cache}, {@code io} — is what a data disk on the same
   * storage should have, and there is then one place in the template that decides it instead of two
   * that can drift apart.
   *
   * <p>Four things are not inherited. {@code <boot>} would put a blank disk in the boot chain.
   * {@code <address>} would collide with the root disk's, so libvirt assigns a free one. {@code
   * <backingStore>} would point the new disk at the base image. Any {@code <serial>} on the root
   * disk is replaced by the disk's name, which is what makes {@code /dev/disk/by-id/virtio-<name>}
   * appear in the guest.
   */
  private Element extraDiskElement(
      Document doc, Element prototype, ExtraDisk disk, String targetDev, String path) {

    Element element = (Element) prototype.cloneNode(true);
    removeChildren(element, "boot");
    removeChildren(element, "address");
    removeChildren(element, "backingStore");
    removeChildren(element, "serial");

    setSourceFile(element, path);

    Element target = (Element) element.getElementsByTagName("target").item(0);
    if (target == null) {
      throw new TemplateException(
          missing(this.templates.getServerTmpl(), "<target> inside <disk device='disk'>"));
    }
    target.setAttribute("dev", targetDev);

    Element serial = doc.createElement("serial");
    serial.setTextContent(disk.getName());
    element.appendChild(serial);
    return element;
  }

  private static void removeChildren(Element parent, String tag) {
    NodeList nodes = parent.getElementsByTagName(tag);
    for (int i = nodes.getLength() - 1; i >= 0; i--) {
      Node node = nodes.item(i);
      node.getParentNode().removeChild(node);
    }
  }

  private void setInterfaceNetwork(Document doc) {
    Element iface = (Element) doc.getElementsByTagName("interface").item(0);
    if (iface == null) {
      throw new TemplateException(missing(this.templates.getServerTmpl(), "<interface>"));
    }
    Element source = (Element) iface.getElementsByTagName("source").item(0);
    if (source == null) {
      throw new TemplateException(
          missing(this.templates.getServerTmpl(), "<source> inside <interface>"));
    }
    source.setAttribute("network", this.network);
  }

  // YAML builders

  private String buildMetaData() {
    try {
      Map<String, Object> yaml = loadYamlTemplate(this.templates.getMetaDataTmpl());
      yaml.put("instance-id", getId());
      yaml.put("local-hostname", getName());
      return yamlMapper().writeValueAsString(yaml);
    } catch (IOException e) {
      throw new TemplateException("Failed to build meta-data YAML for '" + getName() + "'", e);
    }
  }

  public String buildUserDataYaml() {
    Map<String, Object> phoneHome = new LinkedHashMap<>();
    phoneHome.put("url", seedUrl() + "phone-home");
    phoneHome.put("post", List.of("instance_id", "hostname", "fqdn"));
    phoneHome.put("tries", 10);

    try {
      Map<String, Object> yaml = loadYamlTemplate(this.templates.getUserDataTmpl());
      yaml.put("hostname", getName());
      yaml.put("fqdn", getName());
      yaml.put("phone_home", phoneHome);

      /* Map<String, Object> network = (Map<String, Object>) yaml.get("network");
      Map<String, Object> ethernets = (Map<String, Object>) network.get("ethernets");
      Map<String, Object> enp1s0 = (Map<String, Object>) ethernets.get("enp1s0");
      enp1s0.put("addresses", List.of(this.ip));
      enp1s0.put("gateway4", this.gateway); */
      return "#cloud-config\n" + yamlMapper().writeValueAsString(yaml);
    } catch (IOException e) {
      throw new TemplateException("Failed to build user-data YAML for '" + getName() + "'", e);
    }
  }

  public String buildNetworkConfigYaml() {
    String tmpl = this.templates.getNetworkConfigTmpl();
    try {
      Map<String, Object> yaml = loadYamlTemplate(tmpl);
      Map<String, Object> ethernets = requireMap(yaml, "ethernets", tmpl);
      Map<String, Object> vif0 = requireMap(ethernets, "vif0", tmpl);
      vif0.put("addresses", List.of(this.ip));
      vif0.put("gateway4", this.gateway);
      return yamlMapper().writeValueAsString(yaml);
    } catch (IOException e) {
      throw new TemplateException("Failed to build network-config YAML for '" + getName() + "'", e);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> requireMap(Map<String, Object> parent, String key, String tmpl) {
    Object value = parent.get(key);
    if (value == null) {
      throw new TemplateException(
          "Malformed network-config template '"
              + tmpl
              + "': missing '"
              + key
              + "' for '"
              + getName()
              + "'");
    }
    if (!(value instanceof Map)) {
      throw new TemplateException(
          "Malformed network-config template '"
              + tmpl
              + "': '"
              + key
              + "' must be a mapping for '"
              + getName()
              + "'");
    }
    return (Map<String, Object>) value;
  }

  // YAML helpers

  private static ObjectMapper yamlMapper() {
    return new ObjectMapper(new YAMLFactory());
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> loadYamlTemplate(String path) throws IOException {
    return yamlMapper().readValue(new File(path), Map.class);
  }
}
