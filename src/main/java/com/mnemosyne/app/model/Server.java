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
import java.util.LinkedHashSet;
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

  static final String HOSTNAME_PATTERN =
      "^(?=.{1,253}$)[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
          + "(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$";

  static final String HOSTNAME_MESSAGE =
      "must be a host name: letters, digits, '-' and '.', labels of at most 63 characters"
          + " not starting or ending with '-' (e.g. web-01 or web-01.example.lan)";

  @Pattern(regexp = HOSTNAME_PATTERN, message = "Server name " + HOSTNAME_MESSAGE)
  private String name;

  @NotBlank(message = "Server id is required")
  @Pattern(regexp = HOSTNAME_PATTERN, message = "Server id " + HOSTNAME_MESSAGE)
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

  @Valid
  @Size(max = 24, message = "At most 24 extra disks per server")
  private List<ExtraDisk> extraDisks = List.of();

  private Map<String, String> extraVolPaths = new LinkedHashMap<>();

  private Templates templates;

  private String volLookup;

  @NotBlank(message = "Cloud-init metadata base URL is required")
  @Pattern(regexp = "^https?://.+", message = "metaUrl must be a valid HTTP/HTTPS URL")
  private String metaUrl;

  private String volPath = null;

  private Set<String> reusedVolPaths = new LinkedHashSet<>();

  private InitMarker init;

  @NotBlank(message = "Libvirt network name is required")
  private String network = "default";

  private boolean launch = true;

  private Boolean autostart;

  public void setExtraDisks(List<ExtraDisk> extraDisks) {
    this.extraDisks = extraDisks == null ? List.of() : extraDisks;
  }

  @AssertTrue(message = "extraDisks names must be unique within a server")
  public boolean isExtraDiskNamesUnique() {
    List<String> names =
        extraDisks.stream().map(ExtraDisk::getName).filter(Objects::nonNull).toList();
    return names.size() == Set.copyOf(names).size();
  }

  public List<String> extraVolNames() {
    return extraDisks.stream().map(d -> d.volName(getName())).toList();
  }

  public record Seed(
      String name, String token, String metaData, String userData, String networkConfig) {}

  public Seed buildSeed() {
    return new Seed(
        getName(),
        requireInit().token(),
        buildMetaData(),
        buildUserDataYaml(),
        buildNetworkConfigYaml());
  }

  private String seedUrl() {
    String base = metaUrl.endsWith("/") ? metaUrl : metaUrl + "/";
    return base + getName() + "/" + requireInit().token() + "/";
  }

  private InitMarker requireInit() {
    if (init == null || init.token() == null)
      throw new IllegalStateException("No init marker with a token for server '" + getName() + "'");
    return init;
  }

  public static String specHash(int cpu, long ram) {
    String spec = String.join("\u001f", String.valueOf(cpu), String.valueOf(ram));
    return Sha256Util.sha256Hex(spec);
  }

  public String getName() {
    return (name == null || name.isBlank()) ? id : name;
  }

  public String getVolName() {
    return getName() + ".qcow2";
  }

  public String getSpecHash() {
    return specHash(this.cpu, this.ram);
  }

  public String buildVolumeXml() {
    String tmpl = this.templates.getVolTmpl();
    Document doc = loadXmlTemplate(tmpl);
    setElementText(doc, tmpl, "name", getVolName());
    setElementText(doc, tmpl, "capacity", String.valueOf(this.disk));
    return documentToString(doc);
  }

  public String buildExtraVolumeXml(ExtraDisk disk) {
    String tmpl = this.templates.getVolTmpl();
    Document doc = loadXmlTemplate(tmpl);
    setElementText(doc, tmpl, "name", disk.volName(getName()));
    setElementText(doc, tmpl, "capacity", String.valueOf(disk.getSize()));
    return documentToString(doc);
  }

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
    appendMetadata(doc);
    setCloudInitSerial(doc);
    setDiskSource(doc);
    appendExtraDisks(doc);
    setInterfaceNetwork(doc);
    return documentToString(doc);
  }

  public GuestNetwork guestNetwork() {
    return new GuestNetwork(this.ip, this.gateway);
  }

  public String buildMnemosyneMetadataXml(
      List<String> ownedPaths, List<String> reusedPaths, InitMarker init, GuestNetwork network) {
    StringBuilder disks = new StringBuilder();
    for (String p : ownedPaths) disks.append(String.format("<disk path='%s'/>", p));
    StringBuilder reused = new StringBuilder();
    for (String p : reusedPaths) reused.append(String.format("<volume path='%s'/>", p));
    return String.format(
        "<mnemosyne>"
            + "<managedBy>mnemosyne</managedBy>"
            + "<serverId>%s</serverId>"
            + "<specVersion>1</specVersion>"
            + "<disks>%s</disks>"
            + "<reused-disks>%s</reused-disks>"
            + "%s"
            + "%s"
            + "</mnemosyne>",
        getId(),
        disks,
        reused,
        init == null ? "" : init.toXml(),
        network == null ? "" : network.toXml());
  }

  private void appendMetadata(Document doc) {
    String ns = "https://mnemosyne.dev/schema/v1";
    Node meta = doc.getElementsByTagNameNS(ns, "serverId").item(0).getParentNode();
    meta.appendChild(pathList(doc, ns, "mnem:disks", "mnem:disk", ownedPaths()));
    meta.appendChild(
        pathList(doc, ns, "mnem:reused-disks", "mnem:volume", List.copyOf(reusedVolPaths)));
    meta.appendChild(initElement(doc, ns));
    if (this.ip != null) meta.appendChild(networkElement(doc, ns));
  }

  private Element networkElement(Document doc, String ns) {
    Element element = doc.createElementNS(ns, "mnem:network");
    element.setAttribute("ip", this.ip);
    if (this.gateway != null) element.setAttribute("gateway", this.gateway);
    return element;
  }

  private Element initElement(Document doc, String ns) {
    InitMarker marker = requireInit();
    Element element = doc.createElementNS(ns, "mnem:init");
    element.setAttribute("state", marker.state());
    element.setAttribute("token", marker.token());
    if (marker.created() != null) element.setAttribute("created", marker.created());
    return element;
  }

  private static Element pathList(
      Document doc, String ns, String listTag, String itemTag, List<String> paths) {
    Element list = doc.createElementNS(ns, listTag);
    for (String path : paths) {
      Element item = doc.createElementNS(ns, itemTag);
      item.setAttribute("path", path);
      list.appendChild(item);
    }
    return list;
  }

  public List<String> ownedPaths() {
    List<String> paths = new java.util.ArrayList<>();
    if (this.volPath != null) paths.add(this.volPath);
    paths.addAll(this.extraVolPaths.values());
    paths.removeAll(this.reusedVolPaths);
    return paths;
  }

  private void setElementTextNS(Document doc, String namespaceUri, String localName, String value) {
    NodeList nodes = doc.getElementsByTagNameNS(namespaceUri, localName);
    if (nodes.getLength() == 0) {
      throw new TemplateException(
          missing(this.templates.getServerTmpl(), "<{" + namespaceUri + "}" + localName + ">"));
    }
    nodes.item(0).setTextContent(value);
  }

  private String missing(String tmpl, String what) {
    return "Template '" + tmpl + "' has no " + what + " to fill in for server '" + getName() + "'";
  }

  private Document loadXmlTemplate(String path) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setIgnoringComments(true);
      DocumentBuilder builder = factory.newDocumentBuilder();
      Document doc = builder.parse(new File(path));
      stripBlankText(doc.getDocumentElement());
      return doc;
    } catch (ParserConfigurationException | SAXException | IOException e) {
      throw new XmlParseException("Failed to load XML template: " + path, e);
    }
  }

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

      return "#cloud-config\n" + yamlMapper().writeValueAsString(yaml);
    } catch (IOException e) {
      throw new TemplateException("Failed to build user-data YAML for '" + getName() + "'", e);
    }
  }

  public String buildNetworkConfigYaml() {
    try {
      return yamlMapper().writeValueAsString(networkConfig());
    } catch (IOException e) {
      throw new TemplateException("Failed to build network-config YAML for '" + getName() + "'", e);
    }
  }

  public String buildNetplanYaml() {
    try {
      Map<String, Object> yaml = networkConfig();
      Map<String, Object> vif0 = vif0(yaml, this.templates.getNetworkConfigTmpl());
      if (this.gateway == null) vif0.remove("gateway4");
      return yamlMapper().writeValueAsString(Map.of("network", yaml));
    } catch (IOException e) {
      throw new TemplateException("Failed to build netplan YAML for '" + getName() + "'", e);
    }
  }

  private Map<String, Object> networkConfig() throws IOException {
    String tmpl = this.templates.getNetworkConfigTmpl();
    Map<String, Object> yaml = loadYamlTemplate(tmpl);
    Map<String, Object> vif0 = vif0(yaml, tmpl);
    vif0.put("addresses", List.of(this.ip));
    vif0.put("gateway4", this.gateway);
    return yaml;
  }

  private Map<String, Object> vif0(Map<String, Object> yaml, String tmpl) {
    return requireMap(requireMap(yaml, "ethernets", tmpl), "vif0", tmpl);
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

  private static ObjectMapper yamlMapper() {
    return new ObjectMapper(new YAMLFactory());
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> loadYamlTemplate(String path) throws IOException {
    return yamlMapper().readValue(new File(path), Map.class);
  }
}
