package com.mnemosyne.app.model;

import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.libvirt.Connect;
import org.libvirt.Domain;
import org.libvirt.StorageVol;
import org.mockito.InOrder;

public class MnemonReconcileTest {
  // Arange
  @Test
  void reconcileTest() throws Exception {

    Connect connect = mock(Connect.class);
    Domain domain = mock(Domain.class);
    StorageVol vol = mock(StorageVol.class);
    Plan plan = mock(Plan.class);

    String xml =
        """
        <domain type='kvm'>
          <name>toDelete</name>
          <devices>
            <disk type='file' device='disk'>
              <source file='/var/lib/libvirt/images/toDelete.qcow2'/>
            </disk>
          </devices>
        </domain>
        """;

    when(plan.getToDelete()).thenReturn(List.of("toDelete"));
    when(connect.domainLookupByName("toDelete")).thenReturn(domain);
    when(domain.getXMLDesc(0)).thenReturn(xml);
    when(connect.storageVolLookupByPath("/var/lib/libvirt/images/toDelete.qcow2")).thenReturn(vol);
    when(domain.isActive()).thenReturn(1);

    Mnemon m = new Mnemon();
    m.setConnect(connect);
    m.setPlan(plan);

    // Act
    m.reconcile();

    // Assert
    InOrder inOder = inOrder(domain, vol);
    inOder.verify(domain).destroy();
    inOder.verify(domain).undefine();
    inOder.verify(vol).delete(0);
    inOder.verify(vol).free();
    inOder.verify(domain).free();
  }
}
