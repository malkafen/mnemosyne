# Configuration

[← README](../README.md)

- [Inventory](#inventory)
- [Command line](#command-line)
- [Exit codes](#exit-codes)
- [Templates](#templates)
- [Requirements](#requirements)

## Inventory

The inventory is a YAML **list of groups**. A group is one hypervisor plus a **map** of the VMs
that belong on it, keyed by server id. `volLookup`, `metaUrl` and the `templates` block may be set
once per group and are inherited by every server, which can still override them.

[`configs/servers.example.yml`](../configs/servers.example.yml) documents every field with its
default and constraints — copy it and edit:

```bash
cp configs/servers.example.yml configs/servers.yml
```

Local inventories (`configs/servers.yml`, `configs/servers_prod.yml`, `configs/servers_dev.yml`)
are git-ignored.

```yaml
- group: "hv01.example.lan"               # label shown in the plan
  host: "192.0.2.10"                      # hypervisor address
  user: "virtops"                         # SSH user
  port: 22
  key: "/home/virtops/.ssh/id_ed25519"    # private key, absolute path; ~ is not expanded
  volLookup: "debian-13-genericcloud-amd64.qcow2"   # base image in the pool
  metaUrl: "http://192.0.2.5:8080/cloud-init/"      # must be reachable from inside the guest
  servers:
    web-01.example.lan:                   # server id; also the domain name unless `name` is set
      cpu: 2                              # 1-128
      ram: 2048                           # MiB, 256-1048576
      ip: "192.0.2.40/24"                 # CIDR
      gateway: "192.0.2.1"
      disk: 30                            # GiB, minimum 10; raising it grows the disk
      pool: "default"
      network: "host-bridge"              # libvirt network name
      launch: true                        # must be running; reconciled on every run
      autostart: false                    # optional; omitted, libvirt's setting is left alone
      extraDisks:                         # optional, see docs/disks.md
        - name: data                      # -> /dev/disk/by-id/virtio-data
          size: 40                        # GiB, at least 1
        - name: logs
          size: 50
          pool: "fast-ssd"                # optional; defaults to the VM's pool
```

| Field | Level | Default | Notes |
| --- | --- | --- | --- |
| `group` | group | required | Label only. |
| `host`, `port`, `user`, `key` | group | required | SSH target of libvirt. Two groups with the same `host:port` are rejected. |
| `volLookup` | group / server | `noble-server-cloudimg-amd64.img` | Base cloud image; must exist in the VM's `pool`. |
| `metaUrl` | group / server | `http://127.0.0.1:80/files/` — **set it** | Base URL of the [seed server](cloud-init.md#the-seed-server), `http://<this host>:8080/cloud-init/`. |
| `templates` | group / server | `/app/templates/*` | Merged key by key over the defaults, see [templates](#templates). |
| `name` | server | the map key | Domain, volume and host name. Can be changed without recreating the VM. |
| `cpu` | server | required | 1-128. Reconciled on every run, applied on the next boot. |
| `ram` | server | required | MiB, 256-1048576. Same as `cpu`. |
| `ip` | server | required | CIDR. Written into `network-config` at creation only. |
| `gateway` | server | none | Plain IPv4. Same as `ip`. |
| `disk` | server | `30` | Root disk in GiB, at least 10. Raising it [grows the disk](disks.md#growing-a-disk); lowering it blocks the run. |
| `pool` | server | `default` | Storage pool of the root disk. |
| `network` | server | `default` | libvirt network the first `<interface>` is attached to. |
| `launch` | server | `true` | Desired power state. A new VM boots once regardless, for cloud-init. |
| `autostart` | server | untouched | libvirt autostart; omitted, the domain's own setting is left alone. |
| `extraDisks` | server | none | Up to 24 blank disks, see [disks](disks.md). |

`ip`, `gateway`, `name` in `user-data` and everything else rendered into cloud-init only matters on
the first boot: changing them later does not reconfigure an existing guest.

When running in Docker, `key` and template paths are paths **inside the container**.

## Command line

```bash
java -Djna.library.path=/usr/lib/x86_64-linux-gnu -jar target/mnemosyne-*.jar -f configs/servers.yml [flags]
```

`-Djna.library.path` points JNA at the native libvirt library: `/usr/lib/x86_64-linux-gnu` on
Debian/Ubuntu, `/usr/lib64` on RHEL/Fedora, `/opt/homebrew/lib` on macOS. The Docker image needs
none of it.

| Flag | Description | Default |
| --- | --- | --- |
| `-f`, `--servers-file <path>` | Path to the inventory YAML | `/etc/mnemosyne/servers.yml` |
| `-p`, `--plan` | Print the plan and exit without applying | off |
| `-j`, `--join` | [Adopt](how-it-works.md#adopting-existing-vms) matching unmanaged domains; create and delete nothing | off |
| `--no-delete` | Keep managed domains that are absent from the inventory | off |
| `--purge-disks` | When deleting a VM, also delete volumes Mnemosyne did not create — an adopted VM's disks, disks attached by hand, reused volumes. A volume another domain uses is still kept | off |
| `--parallel <n>` | How many VMs of a group to apply at once ([details](how-it-works.md#applying)) | `1` |
| `-v`, `--verbose` | Debug logging, including full stack traces | off |
| `-h`, `--help`, `-V`, `--version` | Usage and version | — |

## Exit codes

| Code | Meaning |
| --- | --- |
| `0` | The host matches the inventory: everything planned was applied and every new guest reported back |
| `1` | Nothing was applied (invalid inventory, a blocked plan, a fatal error), or the run finished incomplete — an entry was skipped, a guest never finished cloud-init, or a VM of the inventory is still [pending](cloud-init.md#initialization-marker) from an earlier run (`--plan` included) |
| `2` | Invalid command line |

## Templates

Five templates are rendered per VM. Paths are set in a `templates` block at group or server level;
each one not given falls back to the default:

```yaml
templates:
  serverTmpl: "/app/templates/server.xml"             # libvirt domain XML
  volTmpl: "/app/templates/volume.xml"                # storage volume XML (root and extra disks)
  metaDataTmpl: "/app/templates/meta-data.yml"        # cloud-init meta-data
  userDataTmpl: "/app/templates/user-data.yml"        # cloud-init user-data
  networkConfigTmpl: "/app/templates/network-config.yml"   # cloud-init network-config v2
```

The defaults are where the [Docker image](../Dockerfile) puts the shipped [`templates/`](../templates).
Running the jar directly, either copy them to `/app/templates/` or point this block at your
checkout, e.g. `/home/me/mnemosyne/templates/server.xml`. Preflight refuses the run if a template
file is not readable.

Which values Mnemosyne fills in and which are passed through is described, with a diagram, in
[cloud-init → what the guest receives](cloud-init.md#what-the-guest-receives). In short:

- [`server.xml`](../templates/server.xml) and [`volume.xml`](../templates/volume.xml) are reference
  definitions meant to run unchanged on any libvirt/KVM host: no emulator path, no pinned machine
  version, no hand-assigned device addresses. Every element carries a comment explaining why it is
  there. A template lacking an element Mnemosyne must fill in is rejected by name.
- Extra disks are copies of the first `<disk device='disk'>` in `server.xml`, so `bus`, `discard`,
  `cache` and `io` set on the root disk apply to every data disk. `volume.xml` describes them too;
  they are created empty instead of cloned.
- [`user-data.yml`](../templates/user-data.yml) and
  [`network-config.yml`](../templates/network-config.yml) are working defaults with a placeholder
  SSH key; the `*.example.yml` files are the annotated reference.

## Requirements

**Machine running Mnemosyne:** Java 17+ (Maven to build), the libvirt client libraries
(`libvirt0`, `libvirt-clients`, `libvirt-dev`) for JNA, an OpenSSH client, an SSH key accepted by
each hypervisor, and port 8080 reachable from the guests. The Docker image contains everything but
the key; run it with `--network host` so the guests can reach port 8080.

**Each hypervisor:** libvirt with KVM/QEMU reachable over SSH as `user`, a running storage pool
holding the base cloud image named by `volLookup`, and a running libvirt network named by
`network`.
