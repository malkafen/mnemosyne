package com.mnemosyne.app.libvirt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.mnemosyne.app.model.DomainState;
import com.mnemosyne.app.utils.XmlUtil;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.libvirt.Connect;
import org.libvirt.Domain;
import org.libvirt.LibvirtException;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class DomainOpsTest {

  @Mock Connect connect;
  @Mock Domain domain;
  Domain[] domains;

  @BeforeEach
  void setUp() {
    domains = new Domain[] {domain};
  }

  private String loadXml(String name) throws Exception {
    return Files.readString(Path.of(getClass().getResource("/" + name).toURI()));
  }

  @Nested
  @DisplayName("joinDomain()")
  class JoinDomain {

    @Test
    void joinDomain_inactiveDomain_injectsMnemNamespaceWithConfigFlag() throws LibvirtException {
      when(connect.domainLookupByName("toAdopt")).thenReturn(domain);
      when(domain.isActive()).thenReturn(0);
      DomainOps domainOps = new DomainOps(connect);
      boolean result = domainOps.joinDomain("toAdopt", "<mnemosyne/>");
      assertThat(result).isTrue();

      verify(domain)
          .setMetadata(
              eq(Domain.MetadataType.ELEMENT),
              eq("<mnemosyne/>"),
              eq("mnem"),
              eq(XmlUtil.MNEM_NS),
              eq(Domain.ModificationImpact.CONFIG));

      verify(domain).free();
    }

    @Test
    void joinDomain_activeDomain_usesConfigAndLiveFlags() throws LibvirtException {
      when(connect.domainLookupByName("toAdopt")).thenReturn(domain);
      when(domain.isActive()).thenReturn(1);
      DomainOps domainOps = new DomainOps(connect);
      boolean result = domainOps.joinDomain("toAdopt", "<mnemosyne/>");
      assertThat(result).isTrue();

      verify(domain)
          .setMetadata(
              anyInt(),
              anyString(),
              anyString(),
              eq(XmlUtil.MNEM_NS),
              eq(Domain.ModificationImpact.CONFIG | Domain.ModificationImpact.LIVE));
    }
  }

  @Nested
  @DisplayName("destroyDomain()")
  class DestroyDomain {
    @Test
    void destroyDomain_activeDomain_destroysAndFreesHandle() throws LibvirtException {
      when(connect.domainLookupByName("toDelete")).thenReturn(domain);
      when(domain.isActive()).thenReturn(1);
      DomainOps domainOps = new DomainOps(connect);
      domainOps.destroyDomain("toDelete");
      verify(domain).destroy();
      verify(domain).free();
    }

    @Test
    void destroyDomain_inactiveDomain_skipsDestroyButFreesHandle() throws LibvirtException {
      when(connect.domainLookupByName("toDelete")).thenReturn(domain);
      when(domain.isActive()).thenReturn(0);
      DomainOps domainOps = new DomainOps(connect);
      domainOps.destroyDomain("toDelete");
      verify(domain, never()).destroy();
      verify(domain).free();
    }

    @Test
    void destroyDomain_destroyFails_rethrowsAndStillFreesHandle() throws LibvirtException {
      when(connect.domainLookupByName("toDelete")).thenReturn(domain);
      when(domain.isActive()).thenReturn(1);
      DomainOps domainOps = new DomainOps(connect);
      LibvirtException boom = mock(LibvirtException.class);
      doThrow(boom).when(domain).destroy();
      assertThatThrownBy(() -> domainOps.destroyDomain("toDelete")).isSameAs(boom);
      verify(domain).free();
    }
  }

  @Nested
  @DisplayName("undefineDomain()")
  class UndefineDomain {

    @Test
    void undefineDomain_undefinesAndFreesHandle() throws LibvirtException {
      when(connect.domainLookupByName("toDelete")).thenReturn(domain);
      DomainOps domainOps = new DomainOps(connect);
      domainOps.undefineDomain("toDelete");
      verify(domain).undefine();
      verify(domain).free();
    }

    @Test
    void undefineDomain_undefineFails_rethrowsAndStillFreesHandle() throws LibvirtException {
      when(connect.domainLookupByName("toDelete")).thenReturn(domain);
      DomainOps domainOps = new DomainOps(connect);
      LibvirtException boom = mock(LibvirtException.class);
      doThrow(boom).when(domain).undefine();
      assertThatThrownBy(() -> domainOps.undefineDomain("toDelete")).isSameAs(boom);
      verify(domain).free();
    }
  }

  @Nested
  @DisplayName("updateRam()")
  class UpdateRam {

    @Test
    void updateRam_redefinesPersistentConfigWithPatchedXml() throws Exception {
      Domain redefined = mock(Domain.class);
      when(connect.domainLookupByName("test-vm.example.net")).thenReturn(domain);
      when(domain.getXMLDesc(Domain.XMLFlags.INACTIVE)).thenReturn(loadXml("managed-domain.xml"));
      when(connect.domainDefineXML(anyString())).thenReturn(redefined);
      DomainOps domainOps = new DomainOps(connect);
      boolean result = domainOps.updateRam("test-vm.example.net", 4096);
      assertThat(result).isTrue();

      ArgumentCaptor<String> xml = ArgumentCaptor.forClass(String.class);
      verify(connect).domainDefineXML(xml.capture());
      assertThat(XmlUtil.getShortState(xml.getValue()).ram()).isEqualTo(4096);

      assertThat(XmlUtil.getShortState(xml.getValue()).serverId()).isEqualTo("test-vm.example.net");
      assertThat(XmlUtil.getShortState(xml.getValue()).cpu()).isEqualTo(2);

      verify(domain).free();
      verify(redefined).free();
    }

    @Test
    void updateRam_readsStoredConfigNotLiveState() throws Exception {
      when(connect.domainLookupByName("test-vm.example.net")).thenReturn(domain);
      when(domain.getXMLDesc(Domain.XMLFlags.INACTIVE)).thenReturn(loadXml("managed-domain.xml"));
      when(connect.domainDefineXML(anyString())).thenReturn(mock(Domain.class));
      DomainOps domainOps = new DomainOps(connect);
      domainOps.updateRam("test-vm.example.net", 4096);
      verify(domain).getXMLDesc(Domain.XMLFlags.INACTIVE);
    }

    @Test
    void updateRam_defineFails_rethrowsAndStillFreesHandle() throws Exception {
      when(connect.domainLookupByName("test-vm.example.net")).thenReturn(domain);
      when(domain.getXMLDesc(anyInt())).thenReturn(loadXml("managed-domain.xml"));
      LibvirtException boom = mock(LibvirtException.class);
      doThrow(boom).when(connect).domainDefineXML(anyString());
      DomainOps domainOps = new DomainOps(connect);
      assertThatThrownBy(() -> domainOps.updateRam("test-vm.example.net", 4096)).isSameAs(boom);
      verify(domain).free();
    }
  }

  @Nested
  @DisplayName("power")
  class Power {

    @Test
    void startDomain_bootsDomainAndFreesHandle() throws LibvirtException {
      when(connect.domainLookupByName("web-01")).thenReturn(domain);
      DomainOps domainOps = new DomainOps(connect);
      domainOps.startDomain("web-01");
      verify(domain).create();
      verify(domain).free();
    }

    @Test
    void shutdownDomain_inactiveDomain_asksForNothing() throws LibvirtException {
      when(connect.domainLookupByName("web-01")).thenReturn(domain);
      when(domain.isActive()).thenReturn(0);
      DomainOps domainOps = new DomainOps(connect);
      domainOps.shutdownDomain("web-01");
      verify(domain, never()).shutdown();
      verify(domain, never()).destroy();
      verify(domain).free();
    }

    @Test
    void shutdownDomain_guestStopsInTime_isNotDestroyed() throws LibvirtException {
      when(connect.domainLookupByName("web-01")).thenReturn(domain);
      when(domain.isActive()).thenReturn(1, 0);
      DomainOps domainOps = new DomainOps(connect);
      domainOps.shutdownDomain("web-01");
      verify(domain).shutdown();
      verify(domain, never()).destroy();
      verify(domain).free();
    }

    @Test
    void updateAutostart_setsFlagAndFreesHandle() throws LibvirtException {
      when(connect.domainLookupByName("web-01")).thenReturn(domain);
      DomainOps domainOps = new DomainOps(connect);
      domainOps.updateAutostart("web-01", true);
      verify(domain).setAutostart(true);
      verify(domain).free();
    }

    @Test
    void setupDomain_autostartFails_undefinesTheDomainItJustDefined() throws LibvirtException {
      when(connect.domainDefineXML(anyString())).thenReturn(domain);
      when(connect.domainLookupByName("web-01")).thenReturn(domain);
      LibvirtException boom = mock(LibvirtException.class);
      doThrow(boom).when(domain).setAutostart(true);
      DomainOps domainOps = new DomainOps(connect);
      DomainOps.DomainSpec spec = new DomainOps.DomainSpec("web-01", "<domain/>", true, true);
      assertThatThrownBy(() -> domainOps.setupDomain(spec)).isSameAs(boom);
      verify(domain).undefine();
      verify(domain, never()).create();
    }
  }

  @Nested
  @DisplayName("readActualState()")
  class ReadActualState {

    @Test
    void readActualState_managedDomain_mapsXmlToShortState() throws Exception {
      when(connect.listAllDomains(anyInt())).thenReturn(domains);
      when(domain.getXMLDesc(anyInt())).thenReturn(loadXml("managed-domain.xml"));
      DomainOps domainOps = new DomainOps(connect);
      List<DomainState> actual = domainOps.readActualState();

      assertThat(actual).hasSize(1);

      assertThat(actual.get(0).name()).isEqualTo("test-vm.example.net");
      assertThat(actual.get(0).managed()).isTrue();
      assertThat(actual.get(0).diskPaths())
          .containsExactly("/var/lib/libvirt/images/test-vm.qcow2");
      assertThat(actual.get(0).disks())
          .containsExactly(
              new DomainState.Disk("vda", "/var/lib/libvirt/images/test-vm.qcow2", null));
      verify(domains[0]).free();
    }

    @Test
    void readActualState_attachesPowerStateAndAutostart() throws Exception {
      when(connect.listAllDomains(anyInt())).thenReturn(domains);
      when(domain.getXMLDesc(anyInt())).thenReturn(loadXml("managed-domain.xml"));
      when(domain.isActive()).thenReturn(1);
      when(domain.getAutostart()).thenReturn(true);
      DomainOps domainOps = new DomainOps(connect);
      List<DomainState> actual = domainOps.readActualState();
      assertThat(actual.get(0).active()).isTrue();
      assertThat(actual.get(0).autostart()).isTrue();
    }

    @Test
    void readActualState_noDomains_returnsEmptyList() throws LibvirtException {
      when(connect.listAllDomains(anyInt())).thenReturn(new Domain[] {});
      DomainOps domainOps = new DomainOps(connect);
      List<DomainState> actual = domainOps.readActualState();
      assertThat(actual).isEmpty();
    }

    @Test
    void readActualState_getXmlDescFails_rethrowsAndStillFreesAllHandles() throws LibvirtException {
      when(connect.listAllDomains(anyInt())).thenReturn(domains);
      LibvirtException boom = mock(LibvirtException.class);
      DomainOps domainOps = new DomainOps(connect);
      doThrow(boom).when(domain).getXMLDesc(anyInt());
      assertThatThrownBy(() -> domainOps.readActualState()).isSameAs(boom);
      verify(domains[0]).free();
    }
  }

  @Nested
  @DisplayName("getDiskPaths()")
  class GetDiskPaths {
    @Test
    void getDiskPaths_domainWithDisks_returnsPaths() throws Exception {
      when(connect.domainLookupByName("toLookup")).thenReturn(domain);
      when(domain.getXMLDesc(anyInt())).thenReturn(loadXml("managed-domain.xml"));
      DomainOps domainOps = new DomainOps(connect);
      List<String> paths = domainOps.getDiskPaths("toLookup");
      assertThat(paths).containsExactly("/var/lib/libvirt/images/test-vm.qcow2");
      verify(domain).free();
    }

    @Test
    void getDiskPaths_domainWithoutDisks_returnsEmptyList() throws Exception {
      when(connect.domainLookupByName("toLookup")).thenReturn(domain);
      when(domain.getXMLDesc(anyInt())).thenReturn(loadXml("managed-domain-without-disk.xml"));
      DomainOps domainOps = new DomainOps(connect);
      List<String> paths = domainOps.getDiskPaths("toLookup");
      assertThat(paths).isEmpty();
      verify(domain).free();
    }

    @Test
    void getDiskPaths_getXmlDescFails_rethrowsAndStillFreesHandle() throws LibvirtException {
      when(connect.domainLookupByName("toLookup")).thenReturn(domain);
      LibvirtException boom = mock(LibvirtException.class);
      DomainOps domainOps = new DomainOps(connect);
      doThrow(boom).when(domain).getXMLDesc(anyInt());
      assertThatThrownBy(() -> domainOps.getDiskPaths("toLookup")).isSameAs(boom);
      verify(domain).free();
    }
  }
}
