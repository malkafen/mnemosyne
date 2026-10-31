package com.mnemosyne.app.libvirt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.libvirt.Connect;
import org.libvirt.Domain;
import org.libvirt.LibvirtException;
import org.libvirt.StorageVol;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("Growing a disk")
public class ResizeOpsTest {

  private static final long GIB = 1024L * 1024 * 1024;
  private static final String PATH = "/var/lib/libvirt/images/web-01-data.qcow2";

  @Mock Connect connect;

  @Nested
  @DisplayName("DomainOps.blockResize(), a running domain")
  class BlockResize {

    @Mock Domain domain;

    @Test
    void passesTheSizeInBytes_withTheBytesFlag() throws LibvirtException {
      when(connect.domainLookupByName("web-01")).thenReturn(domain);
      new DomainOps(connect).blockResize("web-01", "vdb", 40);
      verify(domain).blockResize("vdb", 40 * GIB, 1);
      verify(domain).free();
    }

    @Test
    void anAbsoluteSizeIsSent_notADelta() throws LibvirtException {
      when(connect.domainLookupByName("web-01")).thenReturn(domain);
      DomainOps ops = new DomainOps(connect);
      ops.blockResize("web-01", "vdb", 40);
      ops.blockResize("web-01", "vdb", 40);
      verify(domain, times(2)).blockResize("vdb", 40 * GIB, 1);
    }

    @Test
    void aFailureIsRethrownAndTheHandleIsStillFreed() throws LibvirtException {
      when(connect.domainLookupByName("web-01")).thenReturn(domain);
      LibvirtException boom = mock(LibvirtException.class);
      doThrow(boom).when(domain).blockResize(anyString(), anyLong(), anyInt());
      DomainOps ops = new DomainOps(connect);
      assertThatThrownBy(() -> ops.blockResize("web-01", "vdb", 40)).isSameAs(boom);
      verify(domain).free();
    }
  }

  @Nested
  @DisplayName("StorageOps.growVolume(), a shut-down domain")
  class GrowVolume {

    @Mock StorageVol vol;

    @Test
    void passesTheSizeInBytes_andNeverTheShrinkFlag() throws LibvirtException {
      when(connect.storageVolLookupByPath(PATH)).thenReturn(vol);
      new StorageOps(connect).growVolume(PATH, 40);
      verify(vol).resize(40 * GIB, 0);
      verify(vol).free();
    }

    @Test
    void aFailureIsRethrownAndTheHandleIsStillFreed() throws LibvirtException {
      when(connect.storageVolLookupByPath(PATH)).thenReturn(vol);
      LibvirtException boom = mock(LibvirtException.class);
      doThrow(boom).when(vol).resize(anyLong(), anyInt());
      StorageOps ops = new StorageOps(connect);
      assertThatThrownBy(() -> ops.growVolume(PATH, 40)).isSameAs(boom);
      verify(vol).free();
    }

    @Test
    void theCapacityItReadsBackIsWholeGiB() throws LibvirtException {
      org.libvirt.StorageVolInfo info = mock(org.libvirt.StorageVolInfo.class);
      info.capacity = 40 * GIB;
      when(connect.storageVolLookupByPath(PATH)).thenReturn(vol);
      when(vol.getInfo()).thenReturn(info);
      var capacity = new StorageOps(connect).capacityGiB(PATH);
      assertThat(capacity).hasValue(40);
    }
  }
}
