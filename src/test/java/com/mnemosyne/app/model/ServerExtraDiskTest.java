package com.mnemosyne.app.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mnemosyne.app.exception.TemplateException;
import com.mnemosyne.app.utils.XmlUtil;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

@DisplayName("Server, extra disks")
public class ServerExtraDiskTest {

  private static ExtraDisk disk(String name, int size, String pool) {
    ExtraDisk d = new ExtraDisk();
    d.setName(name);
    d.setSize(size);
    d.setPool(pool);
    return d;
  }

  private static Server server(String template, ExtraDisk... extras) {
    Templates t = new Templates();
    t.setServerTmpl(ServerExtraDiskTest.class.getResource("/" + template).getPath());
    t.setVolTmpl("templates/volume.xml");

    Server s = new Server();
    s.setId("web-01.example.lan");
    s.setTemplates(t);
    s.setCpu(2);
    s.setRam(2048);
    s.setDisk(30);
    s.setVolPath("/var/lib/libvirt/images/web-01.example.lan.qcow2");
    s.setNetwork("host-bridge");
    s.setMetaUrl("http://192.0.2.5:8080/cloud-init/");
    s.setExtraDisks(List.of(extras));
    return s;
  }

  private static Server provisioned(String template, ExtraDisk... extras) {
    Server s = server(template, extras);
    Map<String, String> paths = new java.util.LinkedHashMap<>();
    for (ExtraDisk d : extras)
      paths.put(d.getName(), "/var/lib/libvirt/images/" + d.volName(s.getName()));
    s.setExtraVolPaths(paths);
    return s;
  }

  @Test
  void volName_carriesTheVmName_soTwoVmsCannotShareAVolume() {
    // A pool's namespace is flat and shared by every VM on the host.
    // Act & Assert
    assertThat(disk("data", 40, "default").volName("web-01")).isEqualTo("web-01-data.qcow2");
    assertThat(disk("data", 40, "default").volName("web-02")).isEqualTo("web-02-data.qcow2");
  }

  @Test
  void buildServerXml_noExtraDisks_leavesTheDomainExactlyAsBefore() {
    // Act
    String xml = provisioned("server-template.xml").buildServerXml();
    // Assert
    assertThat(XmlUtil.disks(java.util.Objects.requireNonNull(parse(xml)))).hasSize(1);
    assertThat(xml).doesNotContain("<serial>");
  }

  @Test
  void buildServerXml_assignsTheNextFreeTargetsInInventoryOrder() {
    // Act
    String xml =
        provisioned("server-template.xml", disk("data", 40, "default"), disk("logs", 50, "nvme"))
            .buildServerXml();
    // Assert
    List<DomainState.Disk> disks = XmlUtil.disks(parse(xml));
    assertThat(disks)
        .containsExactly(
            new DomainState.Disk("vda", "/var/lib/libvirt/images/web-01.example.lan.qcow2", null),
            new DomainState.Disk(
                "vdb", "/var/lib/libvirt/images/web-01.example.lan-data.qcow2", "data"),
            new DomainState.Disk(
                "vdc", "/var/lib/libvirt/images/web-01.example.lan-logs.qcow2", "logs"));
  }

  @Test
  void buildServerXml_writesTheDiskNameAsSerial_soTheGuestGetsAStableByIdPath() {
    // /dev/disk/by-id/virtio-data is the path an administrator can partition without caring
    // which letter the kernel happened to give the disk.
    // Act
    String xml = provisioned("server-template.xml", disk("data", 40, "default")).buildServerXml();
    // Assert
    assertThat(xml).contains("<serial>data</serial>");
  }

  @Test
  void buildServerXml_inheritsTheRootDisksDriverTuning() {
    // The template is the single place that decides what the storage wants; a data disk on the
    // same pool wants the same cache/io/discard settings.
    // Act
    String xml =
        provisioned("server-template-with-cdrom.xml", disk("data", 40, "default")).buildServerXml();
    // Assert
    Element driver = child(dataDisk(xml), "driver");
    assertThat(driver.getAttribute("type")).isEqualTo("qcow2");
    assertThat(driver.getAttribute("discard")).isEqualTo("unmap");
    assertThat(driver.getAttribute("cache")).isEqualTo("none");
    assertThat(driver.getAttribute("io")).isEqualTo("native");
  }

  @Test
  void buildServerXml_doesNotInheritBootOrderAddressOrTheRootSerial() {
    // A blank disk in the boot chain would change which disk the VM boots from; a copied
    // <address> would collide with the root disk's, and a copied <serial> would make two disks
    // answer to /dev/disk/by-id/virtio-root.
    // Act
    String xml =
        provisioned("server-template-with-cdrom.xml", disk("data", 40, "default")).buildServerXml();
    // Assert
    Element data = dataDisk(xml);
    assertThat(child(data, "boot")).isNull();
    assertThat(child(data, "address")).isNull();
    assertThat(child(data, "serial").getTextContent()).isEqualTo("data");

    // The root disk keeps everything it had.
    Element root = diskElements(xml).get(0);
    assertThat(child(root, "boot").getAttribute("order")).isEqualTo("1");
    assertThat(child(root, "address").getAttribute("type")).isEqualTo("drive");
    assertThat(child(root, "serial").getTextContent()).isEqualTo("root");
  }

  @Test
  void buildServerXml_skipsATargetNameTheTemplateAlreadyUses() {
    // The cdrom in this template sits on sdb. Handing sdb to a data disk would either be
    // rejected by libvirt or, worse, quietly shadow the cdrom.
    // Act
    String xml =
        provisioned(
                "server-template-with-cdrom.xml",
                disk("data", 40, "default"),
                disk("logs", 50, "default"))
            .buildServerXml();
    // Assert: the scsi bus is inherited from the root disk and sdb is left alone
    assertThat(XmlUtil.usedDiskTargets(xml)).containsExactly("sda", "sdb", "sdc", "sdd");
    Element target = child(dataDisk(xml), "target");
    assertThat(target.getAttribute("dev")).isEqualTo("sdc");
    assertThat(target.getAttribute("bus")).isEqualTo("scsi");
  }

  @Test
  void buildServerXml_volumeNotProvisioned_failsInsteadOfDefiningADiskWithNoSource() {
    // Arrange: extraVolPaths deliberately left empty
    Server s = server("server-template.xml", disk("data", 40, "default"));
    // Act & Assert
    assertThatThrownBy(s::buildServerXml)
        .isInstanceOf(TemplateException.class)
        .hasMessageContaining("No provisioned volume for extra disk 'data'");
  }

  @Test
  void buildExtraVolumeXml_usesTheDisksOwnSizeAndName() {
    // Act
    Server s = provisioned("server-template.xml", disk("data", 40, "default"));
    String xml = s.buildExtraVolumeXml(s.getExtraDisks().get(0));
    // Assert
    assertThat(xml)
        .contains("<name>web-01.example.lan-data.qcow2</name>")
        .contains("<capacity unit=\"GiB\">40</capacity>")
        .contains("<allocation unit=\"bytes\">0</allocation>");
  }

  @Test
  void buildExtraDiskXml_isASingleDeviceWithNoXmlDeclaration() {
    // libvirt's attachDeviceFlags takes one device, not a document.
    // Act
    Server s = provisioned("server-template.xml", disk("data", 40, "default"));
    String xml =
        s.buildExtraDiskXml(
            s.getExtraDisks().get(0),
            "vdf",
            "/var/lib/libvirt/images/web-01.example.lan-data.qcow2");
    // Assert
    assertThat(xml).doesNotContain("<?xml").doesNotContain("<domain").startsWith("<disk");
    assertThat(xml)
        .contains("<serial>data</serial>")
        .contains("file=\"/var/lib/libvirt/images/web-01.example.lan-data.qcow2\"");
    assertThat(XmlUtil.usedDiskTargets("<devices>" + xml + "</devices>")).containsExactly("vdf");
  }

  @Test
  void extraDiskNamesUnique_rejectsTwoDisksWithOneName() {
    // Two disks called 'data' would resolve to one volume file and one serial, and the second
    // would silently win.
    // Arrange
    Server s = server("server-template.xml", disk("data", 40, "default"), disk("data", 50, "nvme"));
    // Assert
    assertThat(s.isExtraDiskNamesUnique()).isFalse();
    assertThat(
            server("server-template.xml", disk("data", 40, "default"), disk("logs", 50, "nvme"))
                .isExtraDiskNamesUnique())
        .isTrue();
  }

  @Test
  void setExtraDisks_null_isAnEmptyList() {
    // `extraDisks:` written with nothing under it deserializes to null.
    // Arrange
    Server s = new Server();
    // Act
    s.setExtraDisks(null);
    // Assert
    assertThat(s.getExtraDisks()).isEmpty();
    assertThat(s.isExtraDiskNamesUnique()).isTrue();
    assertThat(s.extraVolNames()).isEmpty();
  }

  private static org.w3c.dom.Document parse(String xml) {
    try {
      javax.xml.parsers.DocumentBuilderFactory f =
          javax.xml.parsers.DocumentBuilderFactory.newInstance();
      f.setNamespaceAware(true);
      return f.newDocumentBuilder()
          .parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** The {@code <disk device='disk'>} elements of a domain, in document order. */
  private static List<Element> diskElements(String xml) {
    NodeList nodes = parse(xml).getElementsByTagName("disk");
    List<Element> disks = new java.util.ArrayList<>();
    for (int i = 0; i < nodes.getLength(); i++) {
      Element disk = (Element) nodes.item(i);
      if ("disk".equals(disk.getAttribute("device"))) disks.add(disk);
    }
    return disks;
  }

  /** The first extra disk, that is, the one right after the root disk. */
  private static Element dataDisk(String xml) {
    return diskElements(xml).get(1);
  }

  private static Element child(Element parent, String tag) {
    NodeList nodes = parent.getElementsByTagName(tag);
    return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
  }
}
