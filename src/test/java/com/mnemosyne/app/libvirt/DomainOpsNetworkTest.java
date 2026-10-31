package com.mnemosyne.app.libvirt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.libvirt.Connect;
import org.libvirt.Domain;
import org.libvirt.LibvirtException;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("DomainOps.updateInterfaceNetwork()")
public class DomainOpsNetworkTest {

  private static final String CONFIG_XML =
      "<domain><name>web-01</name><devices><interface type='network'>"
          + "<mac address='52:54:00:46:96:dc'/><source network='lan'/></interface>"
          + "</devices></domain>";

  @Mock Connect connect;
  @Mock Domain domain;

  @Test
  void runningDomain_configFirst_thenLive_withTheSameInterface() throws LibvirtException {
    when(connect.domainLookupByName("web-01")).thenReturn(domain);
    when(domain.getXMLDesc(Domain.XMLFlags.INACTIVE)).thenReturn(CONFIG_XML);

    boolean live = new DomainOps(connect).updateInterfaceNetwork("web-01", "dmz", true);

    assertThat(live).isTrue();
    ArgumentCaptor<String> xml = ArgumentCaptor.forClass(String.class);
    InOrder order = inOrder(domain);
    order.verify(domain).updateDeviceFlags(xml.capture(), eq(Domain.DeviceModifyFlags.CONFIG));
    order.verify(domain).updateDeviceFlags(xml.capture(), eq(Domain.DeviceModifyFlags.LIVE));
    assertThat(xml.getAllValues().get(0))
        .isEqualTo(xml.getAllValues().get(1))
        .contains("network=\"dmz\"")
        .contains("52:54:00:46:96:dc");
    verify(domain, never()).getXMLDesc(0);
    verify(domain).free();
  }

  @Test
  void shutOffDomain_onlyTheConfigIsTouched() throws LibvirtException {
    when(connect.domainLookupByName("web-01")).thenReturn(domain);
    when(domain.getXMLDesc(Domain.XMLFlags.INACTIVE)).thenReturn(CONFIG_XML);

    boolean live = new DomainOps(connect).updateInterfaceNetwork("web-01", "dmz", false);

    assertThat(live).isFalse();
    verify(domain).updateDeviceFlags(anyString(), eq(Domain.DeviceModifyFlags.CONFIG));
    verify(domain, never()).updateDeviceFlags(anyString(), eq(Domain.DeviceModifyFlags.LIVE));
  }

  @Test
  void liveChangeFails_theConfigKeepsTheNewNetwork() throws LibvirtException {
    when(connect.domainLookupByName("web-01")).thenReturn(domain);
    when(domain.getXMLDesc(Domain.XMLFlags.INACTIVE)).thenReturn(CONFIG_XML);
    doReturn(0).when(domain).updateDeviceFlags(anyString(), eq(Domain.DeviceModifyFlags.CONFIG));
    doThrow(mock(LibvirtException.class))
        .when(domain)
        .updateDeviceFlags(anyString(), eq(Domain.DeviceModifyFlags.LIVE));

    boolean live = new DomainOps(connect).updateInterfaceNetwork("web-01", "dmz", true);

    assertThat(live).isFalse();
    verify(domain).updateDeviceFlags(anyString(), eq(Domain.DeviceModifyFlags.CONFIG));
    verify(domain).free();
  }

  @Test
  void configChangeFails_theFailureReachesTheCaller() throws LibvirtException {
    when(connect.domainLookupByName("web-01")).thenReturn(domain);
    when(domain.getXMLDesc(Domain.XMLFlags.INACTIVE)).thenReturn(CONFIG_XML);
    LibvirtException failure = mock(LibvirtException.class);
    when(domain.updateDeviceFlags(anyString(), eq(Domain.DeviceModifyFlags.CONFIG)))
        .thenThrow(failure);

    assertThatThrownBy(() -> new DomainOps(connect).updateInterfaceNetwork("web-01", "dmz", true))
        .isSameAs(failure);
    verify(domain, never()).updateDeviceFlags(anyString(), eq(Domain.DeviceModifyFlags.LIVE));
    verify(domain).free();
  }
}
