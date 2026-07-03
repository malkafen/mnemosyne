package com.mnemosyne.app.libvirt;

import org.libvirt.Connect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


class StorageOps {

  private static final Logger log = LoggerFactory.getLogger(StorageOps.class); 
  private final Connect connect;

    StorageOps(Connect connect){
        this.connect = connect;
    }
}