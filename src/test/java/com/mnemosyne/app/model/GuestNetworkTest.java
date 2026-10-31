package com.mnemosyne.app.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GuestNetwork")
public class GuestNetworkTest {

  private static Server server(String ip, String gateway) {
    Server s = new Server();
    s.setIp(ip);
    s.setGateway(gateway);
    return s;
  }

  @Test
  void theSameAddressAndGatewayIsNoDrift() {
    assertThat(
            new GuestNetwork("10.0.0.5/24", "10.0.0.1")
                .differsFrom(server("10.0.0.5/24", "10.0.0.1")))
        .isFalse();
  }

  @Test
  void anotherPrefixLengthIsDrift() {
    assertThat(
            new GuestNetwork("10.0.0.5/24", "10.0.0.1")
                .differsFrom(server("10.0.0.5/16", "10.0.0.1")))
        .isTrue();
  }

  @Test
  void aGatewayAddedOrRemovedIsDrift() {
    assertThat(new GuestNetwork("10.0.0.5/24", null).differsFrom(server("10.0.0.5/24", "10.0.0.1")))
        .isTrue();
    assertThat(new GuestNetwork("10.0.0.5/24", "10.0.0.1").differsFrom(server("10.0.0.5/24", null)))
        .isTrue();
  }

  @Test
  void theAssumedRecordNeverDrifts() {
    assertThat(GuestNetwork.ASSUMED_CURRENT.differsFrom(server("10.0.0.5/24", "10.0.0.1")))
        .isFalse();
    assertThat(GuestNetwork.ASSUMED_CURRENT.toXml()).isEmpty();
  }

  @Test
  void anEqualRecordReadFromXmlIsNotTheAssumedOne() {
    assertThat(new GuestNetwork(null, null).assumed()).isFalse();
  }

  @Test
  void theXmlCarriesTheGatewayOnlyWhenThereIsOne() {
    assertThat(new GuestNetwork("10.0.0.5/24", "10.0.0.1").toXml())
        .isEqualTo("<network ip='10.0.0.5/24' gateway='10.0.0.1'/>");
    assertThat(new GuestNetwork("10.0.0.5/24", null).toXml())
        .isEqualTo("<network ip='10.0.0.5/24'/>");
  }
}
