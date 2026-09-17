package com.mnemosyne.app.libvirt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.mnemosyne.app.model.Preflight.Problem;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.libvirt.Connect;
import org.libvirt.LibvirtException;
import org.libvirt.Network;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("NetworkOps.checkNetwork()")
public class NetworkOpsTest {

  @Mock Connect connect;
  @Mock Network network;

  @Test
  void checkNetwork_unknownName_isReported() throws LibvirtException {
    // Arrange
    when(connect.networkLookupByName("br-lan")).thenThrow(mock(LibvirtException.class));
    // Act
    Optional<Problem> problem = new NetworkOps(connect).checkNetwork("br-lan");
    // Assert
    assertThat(problem).contains(new Problem("network 'br-lan'", "not found on the host"));
  }

  @Test
  void checkNetwork_definedButStopped_isReportedAndHandleFreed() throws LibvirtException {
    // Arrange
    when(connect.networkLookupByName("br-lan")).thenReturn(network);
    when(network.isActive()).thenReturn(0);
    // Act
    Optional<Problem> problem = new NetworkOps(connect).checkNetwork("br-lan");
    // Assert
    assertThat(problem).map(Problem::reason).get().asString().contains("not running");
    verify(network).free();
  }

  @Test
  void checkNetwork_runningNetwork_passesAndFreesHandle() throws LibvirtException {
    // Arrange
    when(connect.networkLookupByName("default")).thenReturn(network);
    when(network.isActive()).thenReturn(1);
    // Act
    Optional<Problem> problem = new NetworkOps(connect).checkNetwork("default");
    // Assert
    assertThat(problem).isEmpty();
    verify(network).free();
  }
}
