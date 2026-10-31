package com.mnemosyne.app.libvirt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.libvirt.Connect;
import org.libvirt.Domain;
import org.libvirt.LibvirtException;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("DomainOps.attachDisk()")
public class DomainOpsAttachTest {

  private static final String DISK_XML = "<disk device='disk'><target dev='vdb'/></disk>";

  @Mock Connect connect;
  @Mock Domain domain;

  @Test
  void runningDomain_writesTheConfigFirst_thenHotPlugs() throws LibvirtException {
    when(connect.domainLookupByName("web-01")).thenReturn(domain);
    boolean live = new DomainOps(connect).attachDisk("web-01", DISK_XML, true);
    assertThat(live).isTrue();
    InOrder order = inOrder(domain);
    order.verify(domain).attachDeviceFlags(DISK_XML, Domain.DeviceModifyFlags.CONFIG);
    order.verify(domain).attachDeviceFlags(DISK_XML, Domain.DeviceModifyFlags.LIVE);
    verify(domain).free();
  }

  @Test
  void hotPlugFails_theDiskStaysInTheConfigAndTheRunCarriesOn() throws LibvirtException {
    when(connect.domainLookupByName("web-01")).thenReturn(domain);
    doNothing().when(domain).attachDeviceFlags(DISK_XML, Domain.DeviceModifyFlags.CONFIG);
    doThrow(mock(LibvirtException.class))
        .when(domain)
        .attachDeviceFlags(DISK_XML, Domain.DeviceModifyFlags.LIVE);
    boolean live = new DomainOps(connect).attachDisk("web-01", DISK_XML, true);
    assertThat(live).isFalse();
    verify(domain).attachDeviceFlags(DISK_XML, Domain.DeviceModifyFlags.CONFIG);
    verify(domain).free();
  }

  @Test
  void shutOffDomain_onlyTheConfigIsTouched() throws LibvirtException {
    when(connect.domainLookupByName("web-01")).thenReturn(domain);
    boolean live = new DomainOps(connect).attachDisk("web-01", DISK_XML, false);
    assertThat(live).isFalse();
    verify(domain).attachDeviceFlags(DISK_XML, Domain.DeviceModifyFlags.CONFIG);
    verify(domain, never()).attachDeviceFlags(DISK_XML, Domain.DeviceModifyFlags.LIVE);
  }

  @Test
  void configAttachFails_theFailureReachesTheCaller() throws LibvirtException {
    when(connect.domainLookupByName("web-01")).thenReturn(domain);
    doThrow(mock(LibvirtException.class))
        .when(domain)
        .attachDeviceFlags(DISK_XML, Domain.DeviceModifyFlags.CONFIG);
    DomainOps domainOps = new DomainOps(connect);
    assertThatThrownBy(() -> domainOps.attachDisk("web-01", DISK_XML, true))
        .isInstanceOf(LibvirtException.class);
    verify(domain, never()).attachDeviceFlags(DISK_XML, Domain.DeviceModifyFlags.LIVE);
    verify(domain).free();
  }

  @Test
  void usedDiskTargets_readsTheLiveDefinition_soHotPluggedDisksAreSeen() throws LibvirtException {
    String live =
        """
        <domain type="kvm">
          <devices>
            <disk type="file" device="disk">
              <source file="/images/root.qcow2"/>
              <target dev="vda" bus="virtio"/>
            </disk>
            <disk type="file" device="disk">
              <source file="/images/hot.qcow2"/>
              <target dev="vdb" bus="virtio"/>
            </disk>
            <disk type="file" device="cdrom">
              <target dev="hdc" bus="ide"/>
            </disk>
          </devices>
        </domain>
        """;
    when(connect.domainLookupByName("web-01")).thenReturn(domain);
    when(domain.getXMLDesc(0)).thenReturn(live);
    var used = new DomainOps(connect).usedDiskTargets("web-01");
    assertThat(used).containsExactly("vda", "vdb", "hdc");
    verify(domain, never()).getXMLDesc(eq(Domain.XMLFlags.INACTIVE));
    verify(domain).free();
  }

  @Test
  void usedDiskTargets_lookupFails_freesNothingItNeverGot() throws LibvirtException {
    when(connect.domainLookupByName("gone")).thenThrow(mock(LibvirtException.class));
    DomainOps domainOps = new DomainOps(connect);
    assertThatThrownBy(() -> domainOps.usedDiskTargets("gone"))
        .isInstanceOf(LibvirtException.class);
    verify(domain, never()).getXMLDesc(anyInt());
  }
}
