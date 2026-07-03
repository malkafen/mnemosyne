package com.mnemosyne.app.libvirt;
import org.libvirt.Connect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class Harmonia {

    private final DomainOps domainOps;
    private final StorageOps storageOps;
    private static final Logger log = LoggerFactory.getLogger(Harmonia.class); 

    public Harmonia(Connect connect){
        this.domainOps = new DomainOps(connect);
        this.storageOps = new StorageOps(connect);
    }

}