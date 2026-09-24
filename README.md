# Mnemosyne

Declarative provisioning of libvirt/KVM virtual machines. One YAML inventory describes the
virtual machines that should exist on a set of hypervisors; each run reads the domains that
actually exist, prints the difference, and applies it.

Mnemosyne connects to every hypervisor over `qemu+ssh`, clones new disks from a base cloud image
inside a libvirt storage pool, and configures the guests with cloud-init through the NoCloud
datasource served by a built-in HTTP server.

## State model

| Term | Meaning |
| --- | --- |
| Mnemon | One hypervisor group: a libvirt host plus the map of virtual machines that belong on it. Top-level item of the inventory. |
| Server | One virtual machine, that is, one libvirt domain. |
| `serverId` | The inventory map key. It is written into the domain's libvirt metadata and is the only identity Mnemosyne matches on, so a VM can be renamed without being recreated. |
| managed / unmanaged | A domain is managed when it carries `managedBy: mnemosyne` metadata. Pre-existing domains are unmanaged and are never touched unless adopted with `--join`. |
| Plan | The diff between the inventory (desired state) and the domains found on the host (current state), reported as `create`, `update` and `delete`, plus `note` lines for what is seen but deliberately left alone. Unmanaged domains are listed under `--join`, which is what adopting them needs. |
| Extra disk | An additional blank disk of a VM, declared under `extraDisks`. Created and attached when it is missing and grown when the inventory asks for more; never detached, shrunk or deleted while the VM exists. |

## How a run works

1. The inventory is parsed and validated with Jakarta Bean Validation. Any invalid field aborts the
   run with the offending path and message; nothing is contacted before this passes.
2. A libvirt connection is opened per group over
   `qemu+ssh://user@host:port/system?keyfile=<key>&no_verify=1&no_tty=1`. The key must be an
   existing file on the machine running Mnemosyne.
3. Every domain on the host is read back into a state snapshot (name, vCPU, RAM, `serverId`,
   `managedBy`, disk paths) and diffed against the inventory:
   - **create** — inventory entries with no managed domain and no name collision with an unmanaged one;
   - **update** — managed domains whose vCPU count, RAM, power state or autostart flag differs
     from the inventory, that are missing an extra disk the inventory lists, or that have a disk
     smaller than the inventory asks for;
   - **delete** — managed domains no longer listed in the inventory (suppressed by `--no-delete`);
   - **unmanaged** — everything else: never touched, and listed only under `--join`.
4. Everything the planned **create** entries and disk attachments need is checked before the plan
   is printed: every storage pool exists and is running, the pool of a VM being created holds the
   base image `volLookup`, the libvirt `network` exists and is running, the template files are
   readable on the machine running Mnemosyne, and no volume Mnemosyne is about to write to is
   already attached to a different domain.
   Each pool, image and network is looked up once per distinct value, not once per VM, and a group
   with nothing to create is not queried at all. The base image is looked up in libvirt's cache
   first and the pool is refreshed only if the image is missing from it, so an image copied into
   the pool a moment ago is still found. A VM that cannot be created is listed as `blocked` instead
   of `create`, with the reason, and one blocked VM stops the whole run: nothing is created, updated
   or deleted, in any group. `--join` creates nothing and is not checked.
   With `--plan` the run stops after this.
5. The cloud-init server starts on port 8080 under `/cloud-init`, followed by a 10-second
   confirmation window (`Ctrl+C` aborts).
6. Each group is reconciled in the order delete, update, create:
   - **delete** destroys and undefines the domain, then deletes the volumes Mnemosyne created for
     it (recorded in the domain's metadata) — with `--purge-disks`, every volume — skipping any
     that another domain also uses;
   - **update** adds any missing extra disks first and grows the ones that are too small — a
     running domain through QEMU, so the guest sees the new size at once, a shut-down one through
     its volume in the pool — then changes vCPU count via libvirt and RAM by
     redefining the persistent config, then autostart, then power: a domain that should be running
     is started, one that should not is asked to shut down and is destroyed if it is still up after
     60 seconds. vCPU and RAM take effect on the guest's next boot, so a domain that keeps running
     is otherwise left alone;
   - **create** clones the base image (`volLookup`) inside the target pool, resizes it to the
     requested capacity, creates any `extraDisks` as blank volumes, registers the cloud-init seed,
     then defines and boots the domain. A new VM always boots once, whatever `launch` says, so
     cloud-init can configure it; a VM with `launch: false` is shut down again at the end of the
     run. A volume that already carries the expected name is reused; a failed resize is rolled back.
   Failures are per-VM: the entry is reported as skipped and the run continues.
   VMs are applied one at a time unless `--parallel <n>` asks for more. Each of the three phases
   then applies up to `n` of them at once and is finished before the next one starts, so a name
   freed by a delete is free before a create asks for it. Groups are still reconciled one after
   another. Which VM finishes first changes nothing the operator reads: every entry writes its own
   block and the blocks are printed in the plan's order.
7. Mnemosyne waits for every new guest to report back over cloud-init's `phone_home`, polling every
   5 seconds up to 5 minutes. Guests created with `launch: false` are then shut down — also
   `--parallel` of them at a time, which is worth raising here, since a guest that ignores the
   request is waited on for a full minute before it is destroyed — and reported under `Settled`, as
   `initialized` or `cloud-init did not finish`. The connections and the HTTP server are then closed. Starting an existing VM needs no seed and is never awaited: every
   managed VM has already been through cloud-init.
8. The run ends with a closing line and an exit code. A skipped entry or a guest that never phoned
   home leaves the host short of the inventory, so such a run exits non-zero even though
   everything else was applied.

Guests find their configuration through the SMBIOS serial `ds=nocloud;s=<metaUrl><name>/`, which
points cloud-init at `meta-data`, `user-data`, `network-config` and `vendor-data` on Mnemosyne's
HTTP server. `metaUrl` must therefore resolve from inside the guest.

### Adopting existing VMs

`--join` takes over already-running domains without recreating them. The plan lists the unmanaged
domains, and for each one whose name matches an inventory entry Mnemosyne writes the `mnemosyne`
metadata onto the live domain. Nothing is created, changed or deleted; subsequent ordinary runs
simply see those VMs as managed.

## Requirements

On the machine running Mnemosyne: Java 17+, Maven to build, the libvirt client libraries
(`libvirt0`, `libvirt-clients`, `libvirt-dev`) for JNA, an OpenSSH client, and an SSH key accepted
by each hypervisor.

On each hypervisor: libvirt with KVM/QEMU reachable over SSH, a storage pool holding the base cloud
image named by `volLookup`, a libvirt network or bridge matching `network`, and network reachability
from the guests back to Mnemosyne's port 8080.

## Build

```bash
mvn clean package          # shaded jar at target/mnemosyne-<version>.jar
mvn spotless:apply         # google-java-format; CI runs spotless:check and rejects unformatted code
```

## Usage

```bash
# Preview only — no domain is touched.
java -jar target/mnemosyne-*.jar -f ./configs/servers.yml --plan

# Apply, after the 10-second confirmation window.
java -Djna.library.path=/usr/lib/x86_64-linux-gnu \
     -jar target/mnemosyne-*.jar -f ./configs/servers.yml
```

`-Djna.library.path` points JNA at the native libvirt library: `/usr/lib/x86_64-linux-gnu` on
Debian/Ubuntu, `/usr/lib64` on RHEL/Fedora, `/opt/homebrew/lib` on macOS.

| Flag | Description | Default |
| --- | --- | --- |
| `-f`, `--servers-file <path>` | Path to the inventory YAML | `/etc/mnemosyne/servers.yml` |
| `-p`, `--plan` | Print the plan and exit without applying | off |
| `-j`, `--join` | Adopt matching unmanaged domains; create and delete nothing | off |
| `--no-delete` | Keep managed domains that are absent from the inventory | off |
| `--purge-disks` | When deleting a VM, also delete volumes Mnemosyne did not create — an adopted VM's disks, disks attached by hand. A volume another domain uses is still kept | off |
| `--parallel <n>` | How many VMs of a group to apply at once | `1` |
| `-v`, `--verbose` | Debug logging, including full stack traces | off |
| `-h`, `--help`, `-V`, `--version` | Usage and version | — |

| Exit code | Meaning |
| --- | --- |
| `0` | The host matches the inventory: everything planned was applied and every new guest reported back |
| `1` | Either nothing was applied (a blocked plan or a fatal error) or the run finished incomplete — an entry was skipped, or a guest never finished cloud-init |
| `2` | Invalid command line |

### Docker

The multi-stage [`Dockerfile`](Dockerfile) builds the jar and produces a runtime image with the
libvirt client libraries and `templates/` baked in at `/app/templates`.

```bash
docker build -t mnemosyne .
docker run --rm --network host \
  -v "$PWD/configs:/app/configs" \
  -v "$HOME/.ssh:/root/.ssh:ro" \
  mnemosyne -f /app/configs/servers.yml
```

## Configuration

The inventory is a list of groups; `servers` is a map keyed by server id. `volLookup`, `metaUrl` and
the `templates` block may be declared once per group and are inherited by every server, which can
still override them individually.

```yaml
- group: "hv01.example.lan"
  host: "192.0.2.10"
  user: "virtops"
  port: 22
  key: "/home/virtops/.ssh/id_ed25519"    # absolute path; ~ is not expanded
  volLookup: "debian-13-genericcloud-amd64.qcow2"
  metaUrl: "http://192.0.2.5:8080/cloud-init/"
  servers:
    web-01.example.lan:                   # server id; also the domain name unless `name` is set
      cpu: 2                              # 1-128
      ram: 2048                           # MiB, 256-1048576
      ip: "192.0.2.40/24"                 # CIDR
      gateway: "192.0.2.1"
      disk: 30                            # GiB, minimum 10
      pool: "default"
      network: "host-bridge"
      launch: true                        # the VM must be running; reconciled on every run.
                                          # A new VM boots once regardless, for cloud-init.
      autostart: false                    # optional; omitted, libvirt's own setting is left alone
      extraDisks:                         # optional; blank disks, added after the root one
        - name: data                      # -> /dev/disk/by-id/virtio-data in the guest
          size: 40                        # GiB, at least 1
        - name: logs
          size: 50
          pool: "fast-ssd"                # optional; defaults to the VM's own pool
```

[`configs/servers.example.yml`](configs/servers.example.yml) documents every field, its default and
its constraints; copy it and edit:

```bash
cp configs/servers.example.yml configs/servers.yml
```

Local inventories (`configs/servers.yml`, `configs/servers_prod.yml`) are git-ignored.

### Extra disks

`extraDisks` adds blank qcow2 volumes next to the root disk. They reach the guest **raw** —
partitioning, filesystems and `/etc/fstab` are the administrator's job, and nothing in Mnemosyne's
cloud-init touches them.

Each disk's `name` becomes two things: the volume file name in the pool (`web-01-data.qcow2`, with
the VM's name in it because a pool's namespace is flat and shared by every VM on the host) and the
disk's libvirt `<serial>`, which the guest exposes as `/dev/disk/by-id/virtio-<name>`:

```
# inside the guest
/dev/disk/by-id/virtio-data -> ../../vdb
/dev/disk/by-id/virtio-logs -> ../../vdc
```

The serial is also how Mnemosyne recognises the disk on later runs, in preference to the volume file
name. That is what makes renaming a VM through `name:` safe: the disks it already has keep matching,
so it is not handed a second, empty set of them next to the originals.

**Partition the by-id path, not `/dev/vdb`.** The letters are assigned in the order the disks are
listed, continuing after every target name the domain template already uses, so inserting a disk
above an existing one shifts the letters of the ones below it. The by-id path does not move.

Disks are **never removed or shrunk**, and deliberately so: a data disk holds the only copy of
whatever is on it, and Mnemosyne has no way to tell a disk that is safe to destroy from one that is
not. They do grow: raising a `size` in the inventory — or the VM's own `disk:` — is applied on the
next run.

| Change in the inventory | What happens |
| --- | --- |
| A new entry under `extraDisks` | The volume is created and the disk attached — to a VM being created, and to one that already exists. On a running guest it is hot-plugged; if the hypervisor or guest cannot, it is written to the persistent config and appears on the domain's next start, reported as `applies after power cycle`. |
| An entry removed | **Nothing.** The disk stays attached and is reported as `not in the inventory - left as is`. Detach it with `virsh detach-disk` and delete the volume yourself if that is what you want. |
| `size` increased | The disk is grown on the hypervisor, to the new size exactly. A running domain is resized through QEMU and the guest sees the new capacity immediately; a shut-down one has its volume resized in the pool and sees it at its next boot. **The partition and the filesystem inside the guest are not touched** — see below. |
| `size` decreased | **Nothing, and the run stops.** Shrinking a disk destroys whatever sits past the new end, so the VM is listed as `blocked` with both sizes and nothing is applied, in any group, until the inventory says at least what the disk already is. |
| `pool` changed on an existing disk | **Nothing.** Data is never moved between pools; the disk is reported as missing from the new pool and left where it is. |
| A disk attached by hand, outside the inventory | Reported once, never touched — also when the VM is deleted, unless `--purge-disks` is given. |
| The VM removed from the inventory | The volumes Mnemosyne created for it — root and extra disks, recorded in the domain's metadata — are deleted with it. Anything else stays and is listed under the delete as `left as is`: a volume attached by hand or belonging to an adopted VM (`--purge-disks` deletes those too), and always a volume that another domain also uses or that Mnemosyne created but somebody detached from the VM. The plan lists every volume by path before the confirmation window, so nothing disappears unannounced — and `--no-delete` keeps all of them. |

### Growing a disk

Raise `disk:` for the root disk, or an entry's `size:` under `extraDisks`, and the next run makes
the volume that big. Both are applied the same way, and the hypervisor does all of the work:

| The domain is | How it is grown | When the guest sees it |
| --- | --- | --- |
| running | `virDomainBlockResize` — QEMU grows the image and raises a capacity-change event on the virtio device | immediately, no reboot |
| shut down | `virStorageVolResize` on the volume in its pool | at its next boot |

Growing is applied like any other drift, next to vCPU and RAM: there is no separate flag for it.
Run with `--plan` first — a grow is on the update line, as `grow root disk 25G->40G` — and the
ten-second confirmation window is the second chance to stop it.

**What happens inside the guest is yours.** Mnemosyne hands the guest a bigger block device and
stops there; the partition table and the filesystem on it are the administrator's, exactly as they
are for a new extra disk. On a typical Linux guest that is `growpart /dev/vda 1` followed by
`resize2fs` or `xfs_growfs`. A GPT disk also has its backup header left in the middle of the device
after a grow, which most tools report and `sgdisk -e /dev/vda` fixes.

Nothing here is reversible, which is why the reverse is refused outright: an inventory that asks for
less than the disk already is stops the run rather than shrinking anything.

**`applies after power cycle` means stopping and starting the domain**, not rebooting from inside
the guest. A guest reboot keeps the same QEMU process, and with it exactly the devices the domain was
launched with, so a disk that only reached the persistent config stays invisible until the domain
itself is stopped and started: `launch: false` then `launch: true`, or `virsh shutdown` followed by
`virsh start`. A vCPU or RAM change reported as `applies after restart` behaves the same way.

Hot-plug is best-effort by design. libvirt keeps only a small spare of hot-pluggable PCIe ports, so
attaching several disks to one running guest typically places the first and reports the rest as
`applies after power cycle`, with `No more available PCI slots` in the `-v` log. Nothing is lost —
the volumes exist and the disks are in the config — and the next start brings them in.

A volume that already exists under the expected name is **reused, never replaced**: recreating a VM
with the same name gives it its old data disk back, reported as `reused existing volume`. A volume
that is already attached to a *different* domain is the one disk condition that blocks the run
outright, because two domains sharing one qcow2 corrupt it as soon as both are running.

At most 24 extra disks per VM, and each `name` must be 1-20 characters of `a-z`, `0-9` and `-`,
unique within the VM. Twenty is where the `by-id` link truncates for virtio.

### Templates

Five templates are rendered per VM, configured through the `templates` block and defaulting to
`/app/templates/`: `serverTmpl` (domain XML), `volTmpl` (volume XML), `metaDataTmpl`, `userDataTmpl`
and `networkConfigTmpl`. Mnemosyne fills in only the values it owns — domain name, vCPU, RAM, disk
source, network, metadata and NoCloud serial in the XML; `instance-id` and `local-hostname` in
`meta-data`; `hostname`, `fqdn` and `phone_home` in `user-data`; `addresses` and `gateway4` of the
`vif0` interface in `network-config`. Everything else is used as written.

The shipped `templates/user-data.yml` and `templates/network-config.yml` are working defaults with
placeholder credentials — add your own SSH public keys to `user-data.yml` before the first run. The
matching `*.example.yml` files carry the annotated reference.

An extra disk is a copy of the domain template's first `<disk device='disk'>` with its own source,
target and serial, so the `bus`, `discard`, `cache` and `io` settings you give the root disk apply
to every data disk as well. `<boot>`, `<address>` and `<backingStore>` are not copied.
`volTmpl` describes the extra volumes too; they are simply created empty instead of cloned.

`templates/server.xml` and `templates/volume.xml` are reference definitions meant to run unchanged
on any libvirt/KVM host: nothing host-specific is hardcoded — no emulator path, no pinned machine
version, no manually assigned device addresses — so libvirt fills those in from each hypervisor's
own capabilities. Everything that changes guest behaviour is stated explicitly instead, and every
element carries a comment explaining why it is there and what breaks without it. A template that
lacks an element Mnemosyne must fill in is rejected by name rather than producing a half-configured
domain.

## Output

Plan and applied blocks share one format, so they line up entry by entry. Anything that fails is
listed with a `·` marker and does not stop the run.

```
--- Plan ---------------------------------------------
[ hv01.example.lan ]  delete: 1, update: 1, create: 1
  - old-test.example.lan
      /var/lib/libvirt/images/old-test.example.lan.qcow2
      /var/lib/libvirt/images/old-test.example.lan-data.qcow2
  ~ cache-01  (cpu 2->4, attach 'data' 40G as vdb)
  + web-01
      web-01-data.qcow2 40G in pool 'default'

[ hv02.example.lan ]  no changes

Applying in 10s — Ctrl+C to abort...

--- Applied -------------------------------------------
[ hv01.example.lan ]  delete: 1, update: 1, create: 1, skipped: 0
  - old-test.example.lan
  ~ cache-01  (cpu 2->4, attach 'data' 40G as vdb, applies after restart)
      vdb data in pool 'default'
  + web-01
      data 40G in pool 'default'
```

Disk facts Mnemosyne will not act on are printed as `note` lines under the VM they belong to. A VM
that has nothing but notes is counted separately, so `note: 1` never reads as a change that was
applied:

```
--- Plan ---------------------------------------------
[ hv01.example.lan ]  note: 1
  i db-01
      disk 'data' is 40G on the host, 50G in the inventory - left as is
      disk 'vdc' (db-01-scratch.qcow2) is not in the inventory - left as is
```

A prerequisite the host does not have turns a `create` entry into a `blocked` one and stops the run
before the confirmation window — no VM is touched, in any group:

```
--- Plan ---------------------------------------------
[ hv01.example.lan ]  delete: 1, blocked: 1
  - old-test.example.lan
      /var/lib/libvirt/images/old-test.example.lan
  ! web-01  (network 'host-bridg' not found on the host)

Nothing was applied: 1 of 2 group(s) cannot be created as planned.
```

Servers created with `launch: false` are booted for cloud-init and shut down once it is done, in a
closing block of its own:

```
--- Settled -------------------------------------------
[ hv01.example.lan ]  stop: 1
  - standby-01  (initialized)
```

A guest that never reported back is shut down all the same — it must not stay up against the
inventory — but it is not called initialized, and the run says so on its last line and exits `1`:

```
--- Settled -------------------------------------------
[ hv01.example.lan ]  stop: 1
  - standby-01  (cloud-init did not finish)

Run finished incomplete: cloud-init did not finish on 1 server(s): standby-01.
```

With `--join` the plan lists the unmanaged domains instead, marking the ones that can be adopted:

```
--- Plan ---------------------------------------------
[ hv01.example.lan ]  adopt: 1, unmanaged: 1
  + legacy-web.example.lan  (as 'web-01')
  > legacy-db.example.lan

--- Applied -------------------------------------------
[ hv01.example.lan ]  join: 1
  + web-01
```

## Project layout

```
src/main/java/com/mnemosyne/app/
  Mnemosyne.java              entry point: load, validate, plan, preflight, confirm, apply, wait
  config/Config.java          picocli command-line options
  model/Mnemon.java           hypervisor group; inventory loading and inheritance
  model/Server.java           one VM; XML and cloud-init rendering, validation constraints
  model/Templates.java        template paths, group defaults merged with per-server overrides
  model/Plan.java             create/update/delete/adopt/unmanaged diff and its console block
  model/Preflight.java        unmet prerequisites of the planned creations, per VM
  model/DomainState.java      snapshot of a live domain
  libvirt/Hypervisor.java     qemu+ssh connection
  libvirt/Harmonia.java       per-group orchestration: plan, preflight, reconcile, join
  libvirt/DomainOps.java      define, boot, destroy, undefine, update, read metadata
  libvirt/StorageOps.java     clone, resize and delete volumes; pool and base image checks
  libvirt/NetworkOps.java     libvirt network checks
  http/CloudInitServer.java   NoCloud seed server and phone_home tracking
  output/Report.java          plan/preflight/applied console blocks
  utils/XmlUtil.java          domain XML parsing and memory rewriting
configs/                      inventory example
templates/                    domain and volume XML, cloud-init YAML
```

## License

Apache License 2.0 — see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).
