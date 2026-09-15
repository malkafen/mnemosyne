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
| Plan | The diff between the inventory (desired state) and the domains found on the host (current state), reported as `create`, `update`, `delete` plus the list of unmanaged domains. |

## How a run works

1. The inventory is parsed and validated with Jakarta Bean Validation. Any invalid field aborts the
   run with the offending path and message; nothing is contacted before this passes.
2. A libvirt connection is opened per group over
   `qemu+ssh://user@host:port/system?keyfile=<key>&no_verify=1`. The key must be an existing file on
   the machine running Mnemosyne.
3. Every domain on the host is read back into a state snapshot (name, vCPU, RAM, `serverId`,
   `managedBy`, disk paths) and diffed against the inventory:
   - **create** — inventory entries with no managed domain and no name collision with an unmanaged one;
   - **update** — managed domains whose vCPU count or RAM differs from the inventory;
   - **delete** — managed domains no longer listed in the inventory (suppressed by `--no-delete`);
   - **unmanaged** — everything else, reported but untouched.
   With `--plan` the run stops here.
4. The cloud-init server starts on port 8080 under `/cloud-init`, followed by a 10-second
   confirmation window (`Ctrl+C` aborts).
5. Each group is reconciled in the order delete, update, create:
   - **delete** destroys and undefines the domain, then deletes its file-backed volumes;
   - **update** changes vCPU count via libvirt and RAM by redefining the persistent config. Both take
     effect on the next boot of the guest; the running domain is left alone;
   - **create** clones the base image (`volLookup`) inside the target pool, resizes it to the
     requested capacity, registers the cloud-init seed, then defines and boots the domain. A volume
     that already carries the VM's name is reused; a failed resize is rolled back.
   Failures are per-VM: the entry is reported as skipped and the run continues.
6. Mnemosyne waits for every new guest to report back over cloud-init's `phone_home`, polling every
   5 seconds up to 5 minutes, then closes the connections and stops the HTTP server.

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
| `-v`, `--verbose` | Debug logging, including full stack traces | off |
| `-h`, `--help`, `-V`, `--version` | Usage and version | — |

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
```

[`configs/servers.example.yml`](configs/servers.example.yml) documents every field, its default and
its constraints; copy it and edit:

```bash
cp configs/servers.example.yml configs/servers.yml
```

Local inventories (`configs/servers.yml`, `configs/servers_prod.yml`) are git-ignored.

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

## Output

Plan and applied blocks share one format, so they line up entry by entry. Anything that fails is
listed with a `·` marker and does not stop the run.

```
--- Plan ---------------------------------------------
[ hv01.example.lan ]  delete: 1, update: 1, create: 1
  - old-test.example.lan
      /var/lib/libvirt/images/old-test.example.lan
  ~ cache-01  (cpu 2->4)
  + web-01

[ hv02.example.lan ]  no changes

Applying in 10s — Ctrl+C to abort...

--- Applied -------------------------------------------
[ hv01.example.lan ]  delete: 1, update: 1, create: 1, skipped: 0
  - old-test.example.lan
  ~ cache-01  (cpu 2->4, applies after restart)
  + web-01
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
  Mnemosyne.java              entry point: load, validate, plan, confirm, apply, wait
  config/Config.java          picocli command-line options
  model/Mnemon.java           hypervisor group; inventory loading and inheritance
  model/Server.java           one VM; XML and cloud-init rendering, validation constraints
  model/Templates.java        template paths, group defaults merged with per-server overrides
  model/Plan.java             create/update/delete/adopt/unmanaged diff
  model/DomainState.java      snapshot of a live domain
  libvirt/Hypervisor.java     qemu+ssh connection
  libvirt/Harmonia.java       per-group orchestration: plan, reconcile, join
  libvirt/DomainOps.java      define, boot, destroy, undefine, update, read metadata
  libvirt/StorageOps.java     clone, resize and delete volumes
  http/CloudInitServer.java   NoCloud seed server and phone_home tracking
  output/Report.java          plan/applied console blocks
  utils/XmlUtil.java          domain XML parsing and memory rewriting
configs/                      inventory example
templates/                    domain and volume XML, cloud-init YAML
```

## License

Apache License 2.0 — see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).
