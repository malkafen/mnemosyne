package com.mnemosyne.app.config;

import lombok.Getter;

@Getter
public class Config {
  private String serversPath = "/etc/mnemosyne/servers.yml";
  private boolean planOnly = false;
  private boolean join = false;
  private boolean deleteEnabled = true;

  // args mapping
  public Config(String[] args) {
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--servers-file":
          this.serversPath = (args[i + 1]);
          break;
        case "--plan":
          this.planOnly = true;
          break;
        case "--join":
          this.join = true;
          break;
        case "--no-delete":
          this.deleteEnabled = false;
          break;
      }
    }
  }
}
