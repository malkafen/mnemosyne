package com.mnemosyne.app.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.mnemosyne.app.exception.XmlParseException;
import com.mnemosyne.app.utils.*;
import jakarta.validation.constraints.*;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import lombok.Getter;
import lombok.Setter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
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

  private boolean launch = true;
  private Status status = null;

  public static String specHash(int cpu, long ram) {
    String spec = String.join("\u001f", String.valueOf(cpu), String.valueOf(ram));
    return Sha256Util.sha256Hex(spec);
  }

  public void setStatus(Status status) {
    if (status == null) {
      throw new IllegalArgumentException("Status cannot be null");
    }
    this.status = status;
  }

  public String getName() {
    return (name == null || name.isBlank()) ? id : name;
  }

  public String getSpecHash() {
    return specHash(this.cpu, this.ram);
  }

  // XML builders

  public String buildVolumeXml() {
    Document doc = loadXmlTemplate(this.templates.getVolTmpl());
    setElementText(doc, "name", getName());
    setElementText(doc, "capacity", String.valueOf(this.disk));
    return documentToString(doc);
  }

  public String buildServerXml()
      throws ParserConfigurationException, SAXException, IOException, TransformerException {
    Document doc = loadXmlTemplate(this.templates.getServerTmpl());
    setElementText(doc, "name", getName());
    setElementText(doc, "memory", String.valueOf(this.ram));
    setElementText(doc, "vcpu", String.valueOf(this.cpu));
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
      throw new IllegalStateException("Element not found: {" + namespaceUri + "}" + localName);
    }
    nodes.item(0).setTextContent(value);
  }

  // XML helpers

  private Document loadXmlTemplate(String path) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      DocumentBuilder builder = factory.newDocumentBuilder();
      return builder.parse(new File(path));
    } catch (ParserConfigurationException | SAXException | IOException e) {
      throw new XmlParseException("Failed to load XML template: " + path, e);
    }
  }

  private String documentToString(Document doc) {
    try {
      Transformer transformer = TransformerFactory.newInstance().newTransformer();
      transformer.setOutputProperty(OutputKeys.INDENT, "yes");
      StringWriter writer = new StringWriter();
      transformer.transform(new DOMSource(doc), new StreamResult(writer));
      return writer.toString();
    } catch (TransformerException e) {
      throw new XmlParseException("Failed to serialize XML for '" + getName() + "'", e);
    }
  }

  private void setElementText(Document doc, String tag, String value) {
    Element element = (Element) doc.getElementsByTagName(tag).item(0);
    element.setTextContent(value);
  }

  private void setCloudInitSerial(Document doc) {
    NodeList entries = doc.getElementsByTagName("entry");
    for (int i = 0; i < entries.getLength(); i++) {
      Element entry = (Element) entries.item(i);
      if ("serial".equals(entry.getAttribute("name"))) {
        entry.setTextContent("ds=nocloud;s=" + this.metaUrl + getName() + "/");
        break;
      }
    }
  }

  private void setDiskSource(Document doc) {
    NodeList disks = doc.getElementsByTagName("disk");
    for (int i = 0; i < disks.getLength(); i++) {
      Element disk = (Element) disks.item(i);
      // main disk only, not the cdrom
      if ("disk".equals(disk.getAttribute("device"))) {
        Element source = (Element) disk.getElementsByTagName("source").item(0);
        if (source != null) {
          source.setAttribute("file", this.volPath);
        }
        break;
      }
    }
  }

  private void setInterfaceNetwork(Document doc) {
    NodeList ifaces = doc.getElementsByTagName("interface");
    if (ifaces.getLength() > 0) {
      Element iface = (Element) ifaces.item(0);
      Element source = (Element) iface.getElementsByTagName("source").item(0);
      if (source != null) {
        source.setAttribute("network", this.network);
      }
    }
  }

  // YAML builders

  public String buildUserDataYaml() throws Exception {
    Map<String, Object> yaml = loadYamlTemplate(this.templates.getUserDataTmpl());
    yaml.put("hostname", getName());
    yaml.put("fqdn", getName());
    /* Map<String, Object> network = (Map<String, Object>) yaml.get("network");
    Map<String, Object> ethernets = (Map<String, Object>) network.get("ethernets");
    Map<String, Object> enp1s0 = (Map<String, Object>) ethernets.get("enp1s0");
    enp1s0.put("addresses", List.of(this.ip));
    enp1s0.put("gateway4", this.gateway); */
    return "#cloud-config\n" + yamlMapper().writeValueAsString(yaml);
  }

  @SuppressWarnings("unchecked")
  public String buildNetworkConfigYaml() throws Exception {
    Map<String, Object> yaml = loadYamlTemplate(this.templates.getNetworkConfigTmpl());
    Map<String, Object> ethernets = (Map<String, Object>) yaml.get("ethernets");
    Map<String, Object> vif0 = (Map<String, Object>) ethernets.get("vif0");
    vif0.put("addresses", List.of(this.ip));
    vif0.put("gateway4", this.gateway);
    return yamlMapper().writeValueAsString(yaml);
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
