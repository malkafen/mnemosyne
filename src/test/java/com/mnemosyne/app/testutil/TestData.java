package com.mnemosyne.app.testutil;

import com.mnemosyne.app.model.DomainState;
import com.mnemosyne.app.model.Server;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TestData {

  private TestData() {}
  ;

  private static final String[] testServers = {"toCreate", "toUpdate"};
  private static final int testCpu = 6;
  private static final int testRam = 4096;
  private static final String testIp = "192.168.70.70/24";
  private static final String testGateway = "192.168.70.1";

  private static Server ServerFactory(String name) {
    Server s = new Server();
    s.setId(name);
    s.setCpu(testCpu);
    s.setRam(testRam);
    s.setIp(testIp);
    s.setGateway(testGateway);
    return s;
  }

  public static Map<String, Server> sampleServers() {

    Map<String, Server> servers = new HashMap<>();

    for (String name : testServers) {
      Server s = ServerFactory(name);
      servers.put(s.getId(), s);
    }

    return servers;
  }

  private static DomainState domainStateFactory(String name, int cpu, int ram) {
    return new DomainState(name, cpu + 1, ram, name, "1", "mnemosyne");
  }

  public static List<DomainState> sampleDomainStates() {
    return new ArrayList<>(
        List.of(
            domainStateFactory("toUpdate", testCpu, testRam),
            domainStateFactory("toDelete", testCpu, testRam)));
  }
}
