package com.mnemosyne.app.libvirt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
      // Arrange
      when(connect.domainLookupByName("toAdopt")).thenReturn(domain);
      when(domain.isActive()).thenReturn(1);
      DomainOps domainOps = new DomainOps(connect);
      // Act
      boolean result = domainOps.joinDomain("toAdopt", "<mnemosyne/>");
      // Assert
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
      // Arrange
      when(connect.domainLookupByName("toDelete")).thenReturn(domain);
      when(domain.isActive()).thenReturn(1);
      DomainOps domainOps = new DomainOps(connect);
      // Act
      domainOps.destroyDomain("toDelete");
      // Assert
      verify(domain).destroy();
      verify(domain).free();
    }

    @Test
    void destroyDomain_inactiveDomain_skipsDestroyButFreesHandle() throws LibvirtException {
      // Arrange
      when(connect.domainLookupByName("toDelete")).thenReturn(domain);
      when(domain.isActive()).thenReturn(0);
      DomainOps domainOps = new DomainOps(connect);
      // Act
      domainOps.destroyDomain("toDelete");
      // Assert
      verify(domain, never()).destroy();
      verify(domain).free();
    }

    @Test
    void destroyDomain_destroyFails_rethrowsAndStillFreesHandle() throws LibvirtException {
      // Arrange
      when(connect.domainLookupByName("toDelete")).thenReturn(domain);
      when(domain.isActive()).thenReturn(1);
      DomainOps domainOps = new DomainOps(connect);
      LibvirtException boom = mock(LibvirtException.class);
      // Act
      doThrow(boom).when(domain).destroy();
      // Assert
      assertThatThrownBy(() -> domainOps.destroyDomain("toDelete")).isSameAs(boom);
      verify(domain).free();
    }
  }

  @Nested
  @DisplayName("undefineDomain()")
  class UndefineDomain {

    @Test
    void undefineDomain_undefinesAndFreesHandle() throws LibvirtException {
      // Arrange
      when(connect.domainLookupByName("toDelete")).thenReturn(domain);
      DomainOps domainOps = new DomainOps(connect);
      // Act
      domainOps.undefineDomain("toDelete");
      // Assert
      verify(domain).undefine();
      verify(domain).free();
    }

    @Test
    void undefineDomain_undefineFails_rethrowsAndStillFreesHandle() throws LibvirtException {
      // Arrange
      when(connect.domainLookupByName("toDelete")).thenReturn(domain);
      DomainOps domainOps = new DomainOps(connect);
      LibvirtException boom = mock(LibvirtException.class);
      // Act
      doThrow(boom).when(domain).undefine();
      // Assert
      assertThatThrownBy(() -> domainOps.undefineDomain("toDelete")).isSameAs(boom);
      verify(domain).free();
    }
  }
}
