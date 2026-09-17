package com.mnemosyne.app.libvirt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.mnemosyne.app.model.Preflight.Problem;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.libvirt.Connect;
import org.libvirt.Error;
import org.libvirt.LibvirtException;
import org.libvirt.StoragePool;
import org.libvirt.StorageVol;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("StorageOps.checkBaseImage()")
public class StorageOpsPreflightTest {

  @Mock Connect connect;
  @Mock StoragePool pool;
  @Mock StorageVol vol;

  private static LibvirtException noSuchVolume() {
    LibvirtException e = mock(LibvirtException.class);
    Error error = mock(Error.class);
    when(error.getCode()).thenReturn(Error.ErrorNumber.VIR_ERR_NO_STORAGE_VOL);
    when(e.getError()).thenReturn(error);
    return e;
  }

  @Test
  void checkBaseImage_poolMissing_reportsPoolAndLooksNoFurther() throws LibvirtException {
    // Arrange
    when(connect.storagePoolLookupByName("fast")).thenThrow(mock(LibvirtException.class));
    // Act
    Optional<Problem> problem = new StorageOps(connect).checkBaseImage("fast", "noble.img");
    // Assert
    assertThat(problem).contains(new Problem("pool 'fast'", "not found on the host"));
    verify(pool, never()).refresh(anyInt());
  }

  @Test
  void checkBaseImage_poolDefinedButStopped_reportsItAndFreesTheHandle() throws LibvirtException {
    // Arrange
    when(connect.storagePoolLookupByName("fast")).thenReturn(pool);
    when(pool.isActive()).thenReturn(0);
    // Act
    Optional<Problem> problem = new StorageOps(connect).checkBaseImage("fast", "noble.img");
    // Assert
    assertThat(problem).map(Problem::resource).contains("pool 'fast'");
    assertThat(problem).map(Problem::reason).get().asString().contains("not running");
    verify(pool).free();
  }

  @Test
  void checkBaseImage_imageInCache_passesWithoutRefreshing() throws LibvirtException {
    // Arrange
    when(connect.storagePoolLookupByName("default")).thenReturn(pool);
    when(pool.isActive()).thenReturn(1);
    when(pool.storageVolLookupByName("noble.img")).thenReturn(vol);
    when(vol.getPath()).thenReturn("/var/lib/libvirt/images/noble.img");
    // Act
    Optional<Problem> problem = new StorageOps(connect).checkBaseImage("default", "noble.img");
    // Assert
    assertThat(problem).isEmpty();
    verify(pool, never()).refresh(anyInt());
    verify(pool).free();
  }

  @Test
  void checkBaseImage_imageAppearedAfterRefresh_passes() throws LibvirtException {
    // Arrange: a file copied into the pool directory is not in libvirt's cache yet
    LibvirtException missing = noSuchVolume();
    when(connect.storagePoolLookupByName("default")).thenReturn(pool);
    when(pool.isActive()).thenReturn(1);
    when(pool.storageVolLookupByName("noble.img")).thenThrow(missing).thenReturn(vol);
    when(vol.getPath()).thenReturn("/var/lib/libvirt/images/noble.img");
    // Act
    Optional<Problem> problem = new StorageOps(connect).checkBaseImage("default", "noble.img");
    // Assert
    assertThat(problem).isEmpty();
    verify(pool).refresh(0);
  }

  @Test
  void checkBaseImage_imageMissingAfterRefresh_reportsTheImage() throws LibvirtException {
    // Arrange
    LibvirtException missing = noSuchVolume();
    when(connect.storagePoolLookupByName("default")).thenReturn(pool);
    when(pool.isActive()).thenReturn(1);
    when(pool.storageVolLookupByName("noble.img")).thenThrow(missing);
    // Act
    Optional<Problem> problem = new StorageOps(connect).checkBaseImage("default", "noble.img");
    // Assert
    assertThat(problem).contains(new Problem("image 'noble.img'", "not found in pool 'default'"));
    verify(pool).refresh(0);
    verify(pool).free();
  }
}
