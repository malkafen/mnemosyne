package com.mnemosyne.app.libvirt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.mnemosyne.app.utils.XmlUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.libvirt.Connect;
import org.libvirt.Domain;
import org.libvirt.LibvirtException;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class DomainOpsTest {

  @Mock Connect connect;
  @Mock Domain domain;

  @Nested
  @DisplayName("joinDomain()")
  class JoinDomain {

    @Test
    void joinDomain_inactiveDomain_injectsMnemNamespaceWithConfigFlag() throws LibvirtException {
      // Arrange
      when(connect.domainLookupByName("toAdopt")).thenReturn(domain);
      when(domain.isActive()).thenReturn(0);

      DomainOps domainOps = new DomainOps(connect);

      // Act
      boolean result = domainOps.joinDomain("toAdopt", "<mnemosyne/>");

      // Assert
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

      boolean result = new DomainOps(connect).joinDomain("toAdopt", "<mnemosyne/>");

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
  class DestroyDomain {}
}
