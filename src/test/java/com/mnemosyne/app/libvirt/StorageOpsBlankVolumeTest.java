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
    LibvirtException missing = noSuchVolume();
    when(connect.storagePoolLookupByName("default")).thenReturn(pool);
    when(pool.storageVolLookupByName("web-01-data.qcow2")).thenThrow(missing);
    when(pool.storageVolCreateXML("<volume/>", 0)).thenReturn(vol);
    when(vol.getPath()).thenReturn("/var/lib/libvirt/images/web-01-data.qcow2");
    Provisioned result = new StorageOps(connect).provisionBlankVolume(spec());
    assertThat(result)
        .isEqualTo(new Provisioned("/var/lib/libvirt/images/web-01-data.qcow2", false));
    verify(pool, never()).storageVolCreateXMLFrom(any(), any(), anyInt());
    verify(vol, never()).resize(anyLong(), anyInt());
    verify(vol).free();
    verify(pool).free();
  }

  @Test
  void provisionBlankVolume_volumeAlreadyThere_reusesItAndNeverDeletesIt() throws Exception {
    when(connect.storagePoolLookupByName("default")).thenReturn(pool);
    when(pool.storageVolLookupByName("web-01-data.qcow2")).thenReturn(vol);
    when(vol.getPath()).thenReturn("/var/lib/libvirt/images/web-01-data.qcow2");
    Provisioned result = new StorageOps(connect).provisionBlankVolume(spec());
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
    LibvirtException missing = noSuchVolume();
    when(connect.storagePoolLookupByName("default")).thenReturn(pool);
    when(pool.storageVolLookupByName("web-01-data.qcow2")).thenThrow(missing);
    when(pool.storageVolCreateXML("<volume/>", 0)).thenThrow(mock(LibvirtException.class));
    StorageOps storageOps = new StorageOps(connect);
    assertThatThrownBy(() -> storageOps.provisionBlankVolume(spec()))
        .isInstanceOf(LibvirtException.class);
    verify(vol, never()).delete(anyInt());
    verify(pool).free();
  }

  @Test
  void checkPool_runningPool_passesAndReportsItsDirectory() throws Exception {
    when(connect.storagePoolLookupByName("default")).thenReturn(pool);
    when(pool.isActive()).thenReturn(1);
    when(pool.getXMLDesc(0)).thenReturn(POOL_XML);
    PoolCheck check = new StorageOps(connect).checkPool("default");
    assertThat(check.problem()).isEmpty();
    assertThat(check.targetPath()).isEqualTo("/var/lib/libvirt/images");
    verify(pool).free();
    verify(pool, never()).storageVolLookupByName(any());
  }

  @Test
  void checkPool_missingPool_isReportedAndNothingElseIsAsked() throws Exception {
    when(connect.storagePoolLookupByName("nvme")).thenThrow(mock(LibvirtException.class));
    PoolCheck check = new StorageOps(connect).checkPool("nvme");
    assertThat(check.problem()).contains(new Problem("pool 'nvme'", "not found on the host"));
    assertThat(check.targetPath()).isNull();
  }

  @Test
  void checkPool_stoppedPool_isReportedWithHowToStartIt() throws Exception {
    when(connect.storagePoolLookupByName("nvme")).thenReturn(pool);
    when(pool.isActive()).thenReturn(0);
    PoolCheck check = new StorageOps(connect).checkPool("nvme");
    assertThat(check.problem()).map(Problem::reason).get().asString().contains("virsh pool-start");
    verify(pool).free();
  }

  @Test
  void checkPool_poolWithoutADirectory_passesWithNoPath() throws Exception {
    when(connect.storagePoolLookupByName("ceph")).thenReturn(pool);
    when(pool.isActive()).thenReturn(1);
    when(pool.getXMLDesc(0)).thenReturn("<pool type=\"rbd\"><name>ceph</name></pool>");
    PoolCheck check = new StorageOps(connect).checkPool("ceph");
    assertThat(check.problem()).isEmpty();
    assertThat(check.targetPath()).isNull();
  }

  @Test
  void capacityGiB_readsTheVirtualSize() throws Exception {
    StorageVolInfo info = mock(StorageVolInfo.class);
    info.capacity = 40L * 1024 * 1024 * 1024;
    when(connect.storageVolLookupByPath("/images/data.qcow2")).thenReturn(vol);
    when(vol.getInfo()).thenReturn(info);
    var capacity = new StorageOps(connect).capacityGiB("/images/data.qcow2");
    assertThat(capacity).hasValue(40L);
    verify(vol).free();
  }

  @Test
  void capacityGiB_unreadableVolume_isEmptyRatherThanAFailedRun() throws Exception {
    when(connect.storageVolLookupByPath("/images/gone.qcow2"))
        .thenThrow(mock(LibvirtException.class));
    var capacity = new StorageOps(connect).capacityGiB("/images/gone.qcow2");
    assertThat(capacity).isEmpty();
  }
}
