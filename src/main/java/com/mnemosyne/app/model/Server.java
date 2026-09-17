package com.mnemosyne.app.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.mnemosyne.app.exception.TemplateException;
import com.mnemosyne.app.exception.XmlParseException;
import com.mnemosyne.app.utils.*;
import jakarta.validation.constraints.*;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

  public String getSpecHash() {
    return specHash(this.cpu, this.ram);
  }

  // XML builders

  public String buildVolumeXml() {
    String tmpl = this.templates.getVolTmpl();
    Document doc = loadXmlTemplate(tmpl);
    setElementText(doc, tmpl, "name", getName());
    setElementText(doc, tmpl, "capacity", String.valueOf(this.disk));
    return documentToString(doc);
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
    NodeList disks = doc.getElementsByTagName("disk");
    for (int i = 0; i < disks.getLength(); i++) {
      Element disk = (Element) disks.item(i);
      // main disk only, not the cdrom
      if ("disk".equals(disk.getAttribute("device"))) {
        Element source = (Element) disk.getElementsByTagName("source").item(0);
        if (source == null) {
          throw new TemplateException(
              missing(this.templates.getServerTmpl(), "<source> inside <disk device='disk'>"));
        }
        source.setAttribute("file", this.volPath);
        return;
      }
    }
    throw new TemplateException(missing(this.templates.getServerTmpl(), "<disk device='disk'>"));
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
