package com.mnemosyne.app.libvirt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import com.mnemosyne.app.libvirt.StorageOps.PoolCheck;
import com.mnemosyne.app.libvirt.StorageOps.Provisioned;
import com.mnemosyne.app.libvirt.StorageOps.VolumeSpec;
import com.mnemosyne.app.model.Preflight.Problem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.libvirt.Connect;
import org.libvirt.Error;
import org.libvirt.LibvirtException;
import org.libvirt.StoragePool;
import org.libvirt.StorageVol;
import org.libvirt.StorageVolInfo;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("StorageOps, blank volumes")
public class StorageOpsBlankVolumeTest {

  private static final String POOL_XML =
      """
      <pool type="dir">
        <name>default</name>
        <target>
          <path>/var/lib/libvirt/images</path>
        </target>
      </pool>
      """;

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

  private static VolumeSpec spec() {
    return VolumeSpec.blank("web-01-data.qcow2", "default", "<volume/>");
  }

  @Test
  void provisionBlankVolume_volumeAbsent_createsItWithoutCloningOrResizing() throws Exception {
    // A data disk has nothing to clone from, and the volume XML already states its capacity.
    // Arrange
    LibvirtException missing = noSuchVolume();
    when(connect.storagePoolLookupByName("default")).thenReturn(pool);
    when(pool.storageVolLookupByName("web-01-data.qcow2")).thenThrow(missing);
    when(pool.storageVolCreateXML("<volume/>", 0)).thenReturn(vol);
    when(vol.getPath()).thenReturn("/var/lib/libvirt/images/web-01-data.qcow2");
    // Act
    Provisioned result = new StorageOps(connect).provisionBlankVolume(spec());
    // Assert
    assertThat(result)
        .isEqualTo(new Provisioned("/var/lib/libvirt/images/web-01-data.qcow2", false));
    verify(pool, never()).storageVolCreateXMLFrom(any(), any(), anyInt());
    verify(vol, never()).resize(anyLong(), anyInt());
    verify(vol).free();
    verify(pool).free();
  }

  @Test
  void provisionBlankVolume_volumeAlreadyThere_reusesItAndNeverDeletesIt() throws Exception {
    // THE test of this feature. A volume under the expected name may hold the previous life of a
    // VM with the same name. Replacing it to get a blank disk would destroy data Mnemosyne has no
    // way of knowing about, so it is handed over as is and reported as reused.
    // Arrange
    when(connect.storagePoolLookupByName("default")).thenReturn(pool);
    when(pool.storageVolLookupByName("web-01-data.qcow2")).thenReturn(vol);
    when(vol.getPath()).thenReturn("/var/lib/libvirt/images/web-01-data.qcow2");
    // Act
    Provisioned result = new StorageOps(connect).provisionBlankVolume(spec());
    // Assert
    assertThat(result.reused()).isTrue();
    assertThat(result.path()).isEqualTo("/var/lib/libvirt/images/web-01-data.qcow2");

    verify(vol, never()).delete(anyInt());
    verify(vol, never()).wipe();
    verify(vol, never()).resize(anyLong(), anyInt());
    verify(pool, never()).storageVolCreateXML(any(), anyInt());
  }

  @Test
  void provisionBlankVolume_creationFails_deletesNothingAndLetsTheFailureThrough()
      throws Exception {
    // A failed creation leaves the run to report the VM as skipped. There is no volume of ours to
    // clean up and, crucially, nothing is deleted on the way out.
    // Arrange
    LibvirtException missing = noSuchVolume();
    when(connect.storagePoolLookupByName("default")).thenReturn(pool);
    when(pool.storageVolLookupByName("web-01-data.qcow2")).thenThrow(missing);
    when(pool.storageVolCreateXML("<volume/>", 0)).thenThrow(mock(LibvirtException.class));
    StorageOps storageOps = new StorageOps(connect);
    // Act & Assert
    assertThatThrownBy(() -> storageOps.provisionBlankVolume(spec()))
        .isInstanceOf(LibvirtException.class);
    verify(vol, never()).delete(anyInt());
    verify(pool).free();
  }

  @Test
  void checkPool_runningPool_passesAndReportsItsDirectory() throws Exception {
    // The directory is what the volume-ownership check compares full paths against.
    // Arrange
    when(connect.storagePoolLookupByName("default")).thenReturn(pool);
    when(pool.isActive()).thenReturn(1);
    when(pool.getXMLDesc(0)).thenReturn(POOL_XML);
    // Act
    PoolCheck check = new StorageOps(connect).checkPool("default");
    // Assert
    assertThat(check.problem()).isEmpty();
    assertThat(check.targetPath()).isEqualTo("/var/lib/libvirt/images");
    verify(pool).free();
    // No base image is looked up: a blank disk is not cloned from one.
    verify(pool, never()).storageVolLookupByName(any());
  }

  @Test
  void checkPool_missingPool_isReportedAndNothingElseIsAsked() throws Exception {
    // Arrange
    when(connect.storagePoolLookupByName("nvme")).thenThrow(mock(LibvirtException.class));
    // Act
    PoolCheck check = new StorageOps(connect).checkPool("nvme");
    // Assert
    assertThat(check.problem()).contains(new Problem("pool 'nvme'", "not found on the host"));
    assertThat(check.targetPath()).isNull();
  }

  @Test
  void checkPool_stoppedPool_isReportedWithHowToStartIt() throws Exception {
    // Arrange
    when(connect.storagePoolLookupByName("nvme")).thenReturn(pool);
    when(pool.isActive()).thenReturn(0);
    // Act
    PoolCheck check = new StorageOps(connect).checkPool("nvme");
    // Assert
    assertThat(check.problem()).map(Problem::reason).get().asString().contains("virsh pool-start");
    verify(pool).free();
  }

  @Test
  void checkPool_poolWithoutADirectory_passesWithNoPath() throws Exception {
    // rbd and iscsi pools have no <target><path>. That is not a problem, only a limit on what the
    // path-collision check can see.
    // Arrange
    when(connect.storagePoolLookupByName("ceph")).thenReturn(pool);
    when(pool.isActive()).thenReturn(1);
    when(pool.getXMLDesc(0)).thenReturn("<pool type=\"rbd\"><name>ceph</name></pool>");
    // Act
    PoolCheck check = new StorageOps(connect).checkPool("ceph");
    // Assert
    assertThat(check.problem()).isEmpty();
    assertThat(check.targetPath()).isNull();
  }

  @Test
  void capacityGiB_readsTheVirtualSize() throws Exception {
    // Arrange
    // StorageVolInfo has no public constructor; capacity is a public field.
    StorageVolInfo info = mock(StorageVolInfo.class);
    info.capacity = 40L * 1024 * 1024 * 1024;
    when(connect.storageVolLookupByPath("/images/data.qcow2")).thenReturn(vol);
    when(vol.getInfo()).thenReturn(info);
    // Act
    var capacity = new StorageOps(connect).capacityGiB("/images/data.qcow2");
    // Assert
    assertThat(capacity).hasValue(40L);
    verify(vol).free();
  }

  @Test
  void capacityGiB_unreadableVolume_isEmptyRatherThanAFailedRun() throws Exception {
    // The size is only used for a note, so not knowing it costs the note and nothing else.
    // Arrange
    when(connect.storageVolLookupByPath("/images/gone.qcow2"))
        .thenThrow(mock(LibvirtException.class));
    // Act
    var capacity = new StorageOps(connect).capacityGiB("/images/gone.qcow2");
    // Assert
    assertThat(capacity).isEmpty();
  }
}
