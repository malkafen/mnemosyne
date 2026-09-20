package com.mnemosyne.app.libvirt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.mnemosyne.app.model.ExtraDisk;
import com.mnemosyne.app.model.Plan;
import com.mnemosyne.app.model.Server;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.libvirt.Connect;
import org.libvirt.Domain;
import org.libvirt.LibvirtException;
import org.libvirt.StorageVol;
import org.libvirt.StorageVolInfo;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Growing a disk from one end of the run to the other: the snapshot is read, the sizes are attached
 * to it, the plan decides, and the reconciler picks the call that fits the domain's power state.
 *
 * <p>That last choice is the part worth this much setup. A running domain and a shut-down one need
 * two different libvirt calls, and picking the wrong one either fails outright or quietly leaves
 * the guest with a disk QEMU still believes is the old size.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Harmonia, growing disks")
public class HarmoniaGrowTest {

  private static final long GIB = 1024L * 1024 * 1024;
  private static final String NAME = "web-01";
  private static final String ROOT = "/var/lib/libvirt/images/web-01.qcow2";
  private static final String DATA = "/var/lib/libvirt/images/web-01-data.qcow2";

  @Mock Connect connect;
  @Mock Domain domain;
  @Mock StorageVol rootVol;
  @Mock StorageVol dataVol;

  /** A managed domain with a root disk, and optionally one extra disk carrying a serial. */
  private static String domainXml(boolean withData) {
    return """
        <domain type='kvm'>
          <name>web-01</name>
          <metadata>
            <mnem:mnemosyne xmlns:mnem="https://mnemosyne.dev/schema/v1">
              <mnem:managedBy>mnemosyne</mnem:managedBy>
              <mnem:serverId>web-01</mnem:serverId>
              <mnem:specVersion>1</mnem:specVersion>
            </mnem:mnemosyne>
          </metadata>
          <memory unit='KiB'>2097152</memory>
          <vcpu placement='static'>2</vcpu>
          <devices>
            <disk type='file' device='disk'>
              <source file='/var/lib/libvirt/images/web-01.qcow2'/>
              <target dev='vda' bus='virtio'/>
            </disk>
        """
        + (withData
            ? """
                <disk type='file' device='disk'>
                  <source file='/var/lib/libvirt/images/web-01-data.qcow2'/>
                  <target dev='vdb' bus='virtio'/>
                  <serial>data</serial>
                </disk>
            """
            : "")
        + """
          </devices>
        </domain>
        """;
  }

  private static Server server(int rootGiB, boolean launch, ExtraDisk... extras) {
    Server s = new Server();
    s.setId(NAME);
    s.setName(NAME);
    s.setCpu(2);
    s.setRam(2048);
    s.setDisk(rootGiB);
    s.setLaunch(launch);
    s.setExtraDisks(List.of(extras));
    return s;
  }

  private static ExtraDisk extra(String name, int size) {
    ExtraDisk d = new ExtraDisk();
    d.setName(name);
    d.setSize(size);
    d.setPool("default");
    return d;
  }

  private static StorageVolInfo info(long capacityGiB) {
    StorageVolInfo i = mock(StorageVolInfo.class);
    i.capacity = capacityGiB * GIB;
    return i;
  }

  /** The host as libvirt would report it: one domain, in the given power state. */
  private void host(boolean running, boolean withData) throws LibvirtException {
    when(connect.listAllDomains(0)).thenReturn(new Domain[] {domain});
    when(domain.getXMLDesc(Domain.XMLFlags.INACTIVE)).thenReturn(domainXml(withData));
    when(domain.isActive()).thenReturn(running ? 1 : 0);
    when(domain.getAutostart()).thenReturn(false);
  }

  @Test
  void aRunningDomainIsResizedThroughQemu_soTheGuestSeesItAtOnce() throws LibvirtException {
    // Arrange
    host(true, false);
    when(connect.storageVolLookupByPath(ROOT)).thenReturn(rootVol);
    when(rootVol.getInfo()).thenReturn(info(25));
    when(connect.domainLookupByName(NAME)).thenReturn(domain);

    Harmonia harmonia = new Harmonia("hv01", connect);
    // Act
    Plan plan = harmonia.plan(Map.of(NAME, server(40, true)), false);
    harmonia.reconcile();
    // Assert
    assertThat(plan.getToUpdate().get(NAME).diff()).contains("grow root disk 25G->40G");
    verify(domain).blockResize("vda", 40 * GIB, 1);
    // The volume must not be resized behind QEMU's back while QEMU has the file open.
    verify(rootVol, never()).resize(anyLong(), anyInt());
  }

  @Test
  void aShutDownDomainIsResizedThroughItsVolume() throws LibvirtException {
    // There is no QEMU process to ask, so the volume is grown in the pool instead and the guest
    // finds the new size when it next boots.
    // Arrange
    host(false, false);
    when(connect.storageVolLookupByPath(ROOT)).thenReturn(rootVol);
    when(rootVol.getInfo()).thenReturn(info(25));

    Harmonia harmonia = new Harmonia("hv01", connect);
    // Act
    harmonia.plan(Map.of(NAME, server(40, false)), false);
    harmonia.reconcile();
    // Assert
    verify(rootVol).resize(40 * GIB, 0);
    verify(domain, never()).blockResize(anyString(), anyLong(), anyInt());
  }

  @Test
  void anExtraDiskIsGrownByItsOwnTarget_notTheRootDisks() throws LibvirtException {
    // Two disks on one domain, one of them drifting. Addressing the wrong target would grow the
    // root disk instead — and there is no way back from that.
    // Arrange
    host(true, true);
    when(connect.storageVolLookupByPath(ROOT)).thenReturn(rootVol);
    when(connect.storageVolLookupByPath(DATA)).thenReturn(dataVol);
    when(rootVol.getInfo()).thenReturn(info(25));
    when(dataVol.getInfo()).thenReturn(info(10));
    when(connect.domainLookupByName(NAME)).thenReturn(domain);

    Harmonia harmonia = new Harmonia("hv01", connect);
    // Act
    harmonia.plan(Map.of(NAME, server(25, true, extra("data", 20))), false);
    harmonia.reconcile();
    // Assert
    verify(domain).blockResize("vdb", 20 * GIB, 1);
    verify(domain, never()).blockResize(eq("vda"), anyLong(), anyInt());
  }

  @Test
  void sizesThatAlreadyMatchCostNoResizeAtAll() throws LibvirtException {
    // Arrange
    host(true, false);
    when(connect.storageVolLookupByPath(ROOT)).thenReturn(rootVol);
    when(rootVol.getInfo()).thenReturn(info(25));

    Harmonia harmonia = new Harmonia("hv01", connect);
    // Act
    Plan plan = harmonia.plan(Map.of(NAME, server(25, true)), false);
    harmonia.reconcile();
    // Assert
    assertThat(plan.getToUpdate()).isEmpty();
    verify(domain, never()).blockResize(anyString(), anyLong(), anyInt());
    verify(rootVol, never()).resize(anyLong(), anyInt());
  }

  @Test
  void aDiskBiggerThanTheInventoryBlocksTheRun_andIsNeverShrunk() throws LibvirtException {
    // Arrange
    host(true, false);
    when(connect.storageVolLookupByPath(ROOT)).thenReturn(rootVol);
    when(rootVol.getInfo()).thenReturn(info(50));

    Harmonia harmonia = new Harmonia("hv01", connect);
    // Act
    harmonia.plan(Map.of(NAME, server(40, true)), false);
    var preflight = harmonia.preflight();
    // Assert
    assertThat(preflight.ok()).isFalse();
    assertThat(preflight.blockers(NAME))
        .singleElement()
        .asString()
        .contains("50G on the host, 40G in the inventory")
        .contains("never shrunk");
  }

  @Test
  void aVolumeWhoseSizeCannotBeReadIsNeverResized() throws LibvirtException {
    // The lookup fails, the capacity stays unknown, and an unknown size is not a reason to act.
    // Arrange
    host(true, false);
    when(connect.storageVolLookupByPath(ROOT)).thenThrow(mock(LibvirtException.class));

    Harmonia harmonia = new Harmonia("hv01", connect);
    // Act
    Plan plan = harmonia.plan(Map.of(NAME, server(40, true)), false);
    harmonia.reconcile();
    // Assert
    assertThat(plan.getToUpdate()).isEmpty();
    assertThat(plan.getShrinks()).isEmpty();
    verify(domain, never()).blockResize(anyString(), anyLong(), anyInt());
  }

  @Test
  void anUnmanagedDomainIsNotEvenMeasured() throws LibvirtException {
    // Its sizes are nobody's business, and a lookup per disk of every foreign domain on the host
    // would be paid for on every run.
    // Arrange
    when(connect.listAllDomains(0)).thenReturn(new Domain[] {domain});
    when(domain.getXMLDesc(Domain.XMLFlags.INACTIVE))
        .thenReturn(
            domainXml(false)
                .replace("mnemosyne</mnem:managedBy>", "someone-else</mnem:managedBy>"));
    when(domain.isActive()).thenReturn(1);
    when(domain.getAutostart()).thenReturn(false);

    Harmonia harmonia = new Harmonia("hv01", connect);
    // Act
    harmonia.plan(Map.of(NAME, server(40, true)), false);
    // Assert
    verify(connect, never()).storageVolLookupByPath(anyString());
  }
}
