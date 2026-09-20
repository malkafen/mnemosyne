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

/**
 * The two calls that make a disk bigger, and the arguments they must carry.
 *
 * <p>Both are thin wrappers, and both would fail silently in the worst possible way if their units
 * or flags were wrong: a size read as KiB instead of bytes grows a disk 1024-fold, and a resize
 * that carries the SHRINK flag destroys data. That is what is asserted here.
 */
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
      // Without VIR_DOMAIN_BLOCK_RESIZE_BYTES libvirt reads the argument as KiB, and libvirt-java
      // hands both straight to the C API. The flag is the whole difference between 40G and 40T.
      // Arrange
      when(connect.domainLookupByName("web-01")).thenReturn(domain);
      // Act
      new DomainOps(connect).blockResize("web-01", "vdb", 40);
      // Assert
      verify(domain).blockResize("vdb", 40 * GIB, 1);
      verify(domain).free();
    }

    @Test
    void anAbsoluteSizeIsSent_notADelta() throws LibvirtException {
      // Idempotence rests on this: a run that died after the resize and before its report must
      // find the disk already correct, not grow it a second time.
      // Arrange
      when(connect.domainLookupByName("web-01")).thenReturn(domain);
      DomainOps ops = new DomainOps(connect);
      // Act
      ops.blockResize("web-01", "vdb", 40);
      ops.blockResize("web-01", "vdb", 40);
      // Assert
      verify(domain, times(2)).blockResize("vdb", 40 * GIB, 1);
    }

    @Test
    void aFailureIsRethrownAndTheHandleIsStillFreed() throws LibvirtException {
      // Arrange
      when(connect.domainLookupByName("web-01")).thenReturn(domain);
      LibvirtException boom = mock(LibvirtException.class);
      doThrow(boom).when(domain).blockResize(anyString(), anyLong(), anyInt());
      DomainOps ops = new DomainOps(connect);
      // Act
      assertThatThrownBy(() -> ops.blockResize("web-01", "vdb", 40)).isSameAs(boom);
      // Assert
      verify(domain).free();
    }
  }

  @Nested
  @DisplayName("StorageOps.growVolume(), a shut-down domain")
  class GrowVolume {

    @Mock StorageVol vol;

    @Test
    void passesTheSizeInBytes_andNeverTheShrinkFlag() throws LibvirtException {
      // Flags of 0 mean an absolute capacity in bytes with no shrinking allowed, so libvirt itself
      // refuses a size below the current one even if the caller got the comparison wrong.
      // Arrange
      when(connect.storageVolLookupByPath(PATH)).thenReturn(vol);
      // Act
      new StorageOps(connect).growVolume(PATH, 40);
      // Assert
      verify(vol).resize(40 * GIB, 0);
      verify(vol).free();
    }

    @Test
    void aFailureIsRethrownAndTheHandleIsStillFreed() throws LibvirtException {
      // Arrange
      when(connect.storageVolLookupByPath(PATH)).thenReturn(vol);
      LibvirtException boom = mock(LibvirtException.class);
      doThrow(boom).when(vol).resize(anyLong(), anyInt());
      StorageOps ops = new StorageOps(connect);
      // Act
      assertThatThrownBy(() -> ops.growVolume(PATH, 40)).isSameAs(boom);
      // Assert
      verify(vol).free();
    }

    @Test
    void theCapacityItReadsBackIsWholeGiB() throws LibvirtException {
      // The plan compares GiB, so a volume is measured in the same unit the inventory is written
      // in rather than in bytes nobody would recognise.
      // Arrange
      org.libvirt.StorageVolInfo info = mock(org.libvirt.StorageVolInfo.class);
      info.capacity = 40 * GIB;
      when(connect.storageVolLookupByPath(PATH)).thenReturn(vol);
      when(vol.getInfo()).thenReturn(info);
      // Act
      var capacity = new StorageOps(connect).capacityGiB(PATH);
      // Assert
      assertThat(capacity).hasValue(40);
    }
  }
}
