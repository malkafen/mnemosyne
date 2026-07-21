<!-- Language switcher -->
**English** | [Русский](README.ru.md)

# Mnemosyne

> Declarative, Terraform-style provisioning of libvirt/KVM virtual machines, driven by a single YAML inventory and cloud-init.

Mnemosyne reads one YAML file — the **desired state**: which virtual machines should exist on
which hypervisors. It then reads the **current state** (the domains that actually exist on each
host) and applies the difference:

- VMs in the desired state but missing from the current state are **created** (disk cloned from a
  base cloud image, then booted and configured by cloud-init);
- VMs in the current state but no longer in the desired state are **destroyed** (domain undefined
  and its disk volumes deleted).

It talks to each hypervisor over `qemu+ssh` (libvirt), and serves cloud-init data to the freshly
booted VMs from a small built-in HTTP server.

---

## Table of contents

- [How it works](#how-it-works)
- [Concepts & terminology](#concepts--terminology)
- [Requirements](#requirements)
- [Build](#build)
- [Run](#run)
- [Configuration](#configuration)
- [cloud-init templates](#cloud-init-templates)
- [Example output](#example-output)
- [Project layout](#project-layout)

---

## How it works

```
                         ┌──────────────────────────────────────────────────┐
   configs/servers.yml ─▶│  1 · load + validate          desired state      │
                         └────────────────────────┬─────────────────────────┘
                                                  │  qemu+ssh
                                                  ▼
                         ┌──────────────────────────────────────────────────┐
                         │  2 · read existing domains    current state       │
                         │      identity = libvirt metadata (serverId),      │
                         │      not the VM name                              │
                         └────────────────────────┬─────────────────────────┘
                                                  ▼
                         ┌──────────────────────────────────────────────────┐
                         │  3 · plan  =  desired △ current                   │
                         │      + create     ~ update     - delete           │
                         └────────────────────────┬─────────────────────────┘
                          --plan stops here ◀─────┤
                                                  │  else: 10s confirm window
                                                  ▼
                         ┌──────────────────────────────────────────────────┐
                         │  4 · apply (per host)                             │
                         │      delete ▸ update ▸ clone disk ▸ define & boot │
                         └────────────────────────┬─────────────────────────┘
                                                  ▼   SMBIOS serial
                                       ds=nocloud;s=<metaUrl><name>/
                                                  │
                                                  ▼
                         ┌──────────────────────────────────────────────────┐
                         │  CloudInitServer   HTTP :8080/cloud-init/         │
                         │  serves  user-data · meta-data · network-config   │
                         └──────────────────────────────────────────────────┘
```

The run is a single pass (mirrors `Mnemosyne.run`):

1. **Load & validate the desired state** — parse the inventory into a `List<Mnemon>`, each holding a
   **map of VMs keyed by a stable id**, and validate every field with Jakarta Bean Validation. Any
   invalid field aborts the run with a precise message.
2. **Start the cloud-init server** — a built-in HTTP server starts on port **8080** under the
   `/cloud-init` path.
3. **Connect & plan** — for each hypervisor group, open a libvirt connection over
   `qemu+ssh://user@host:port/system?keyfile=…`, read the domains that already exist, and diff the
   **desired state** against this **current state**. Identity is the `serverId` written into each
   domain's libvirt metadata — *not* its name — so a VM can be renamed without being recreated. Every
   domain lands in one of four buckets: `+ create`, `~ update`, `- delete`, or **unmanaged** (a
   pre-existing domain that carries no Mnemosyne metadata). With `--plan` the run stops after printing.
4. **Confirmation window** — a 10-second pause (`Ctrl+C` to abort) before any change is applied.
5. **Apply (per host)** — in order: **delete → update → create**:
   - **Delete** — domains managed by Mnemosyne but no longer in the inventory are destroyed,
     undefined, and their file-backed disks deleted.
   - **Update** — for managed VMs whose spec drifted, reconcile in place (currently vCPU count; RAM
     updates are pending a libvirt-java release).
   - **Create storage** — for each new VM, clone its disk from the base cloud image (`volLookup`)
     inside the target pool and resize it to the requested capacity. Failures roll back the partially
     created volume.
   - **Define & start** — build the domain XML (name, RAM, vCPU, disk, network, the Mnemosyne
     metadata and the cloud-init NoCloud serial), register the VM's `user-data` / `network-config`
     with the HTTP server, define the domain and boot it.
6. **Wait for cloud-init** — poll until every new VM has pulled its config (or a ~100 s timeout:
   20 attempts × 5 s).
7. **Shut down** — free domain handles, close libvirt connections, stop the HTTP server.

cloud-init wiring uses the **NoCloud** datasource: each VM is given the SMBIOS serial
`ds=nocloud;s=<metaUrl><name>/`, which tells cloud-init inside the guest where to fetch its
`meta-data`, `user-data` and `network-config`. Those requests land on Mnemosyne's HTTP server,
which answers from the per-VM data registered during apply.

### Adopting existing VMs (`--join`)

`--join` brings **already-running domains** under Mnemosyne's management without recreating them. In
this mode the plan lists only the *unmanaged* domains, and for each one whose name matches a server in
the inventory, Mnemosyne writes the `mnemosyne` metadata (`managedBy`, `serverId`) onto the live
domain. Nothing is created or deleted — the next normal run simply sees those VMs as managed.

---

## Concepts & terminology

| Term | Meaning |
| --- | --- |
| **Mnemon** | One **hypervisor group**: a libvirt host plus the **map of VMs** (keyed by id) that should live on it. Top-level item in the inventory. |
| **Server** | One **virtual machine** (a libvirt *domain*) belonging to a Mnemon. |
| **serverId** | The **map key** of a server — a stable identity stored in the domain's libvirt metadata. Managed VMs are matched by this id, so you can change a VM's `name` without recreating it. |
| **managed / unmanaged** | A domain is **managed** when it carries Mnemosyne metadata (`managedBy: mnemosyne`); a pre-existing domain Mnemosyne didn't create is **unmanaged** (and can be adopted with `--join`). |
| **Plan** | The `+ create` / `~ update` / `- delete` diff between the desired state (inventory) and the current state (existing domains), plus the list of unmanaged domains. |
| **Templates** | The set of XML/YAML template paths (`serverTmpl`, `volTmpl`, `userDataTmpl`, `networkConfigTmpl`). Set once at the group level and inherited by every VM, or overridden per server. |
| **CloudInitServer** | Built-in HTTP server (`:8080/cloud-init/<vm>/<file>`) that feeds cloud-init data to booting VMs. |
| **volLookup** | Name of the base cloud image inside the pool that new disks are cloned from. |

---

## Requirements

On the machine that **runs** Mnemosyne:

- **Java 17+**
- **Maven** (to build)
- **libvirt client libraries + JNA**: `libvirt0`, `libvirt-clients`, `libvirt-dev`
- **OpenSSH client** (for the `qemu+ssh` transport)
- An SSH key that can reach each hypervisor as the configured `user`

On each **hypervisor host**:

- libvirt + KVM/QEMU, reachable over SSH
- A storage pool containing the **base cloud image** referenced by `volLookup`
  (e.g. `debian-13-genericcloud-amd64-*.qcow2` or `noble-server-cloudimg-amd64.img`)
- A libvirt **network** (or bridge) matching the `network` field
- The Mnemosyne HTTP server (port 8080) must be reachable **from the VMs** at the address you put
  in `metaUrl`

---

## Build

```bash
mvn clean package
```

This produces a shaded uber-jar at `target/mnemosyne-<version>.jar`.

Format the code before pushing:

```bash
find src/main/java -name "*.java" | xargs java -jar google-java-format-*-all-deps.jar -i
```

---

## Run

```bash
# Preview changes only (no VM is touched):
java -jar target/mnemosyne-*.jar --servers-file ./configs/servers.yml --plan

# Apply (10s confirmation window before changes):
java -Djna.library.path=/usr/lib/x86_64-linux-gnu \
     -jar target/mnemosyne-*.jar --servers-file ./configs/servers.yml
```

> `-Djna.library.path` points JNA at the native libvirt library. Typical values:
> `/usr/lib/x86_64-linux-gnu` (Debian/Ubuntu), `/usr/lib64` (RHEL/Fedora),
> `/opt/homebrew/lib` (macOS / Homebrew).

### CLI flags

| Flag | Description | Default |
| --- | --- | --- |
| `--servers-file <path>` | Path to the inventory YAML | `/etc/mnemosyne/servers.yml` |
| `--plan` | Plan only — print the diff and exit without changing anything | off |
| `--join` | Adopt mode — write Mnemosyne metadata onto matching *unmanaged* domains so they become managed. Creates and deletes nothing. | off |

### Docker

A multi-stage [`Dockerfile`](Dockerfile) builds the jar and a runtime image with the libvirt
client libraries and the `templates/` baked in:

```bash
docker build -t mnemosyne .
docker run --rm \
  -v "$PWD/configs:/app/configs" \
  -v "$HOME/.ssh:/root/.ssh:ro" \
  mnemosyne --servers-file /app/configs/servers.yml
```

---

## Configuration

The inventory is a YAML **list of Mnemons** (hypervisor groups). Inside a group, `servers` is a
**map** whose key is the server **id** (a stable identity); the VM's `name` defaults to that key. A
fully commented starter file is provided at
[`configs/servers.example.yml`](configs/servers.example.yml) — copy it and edit:

```bash
cp configs/servers.example.yml configs/servers.yml
```

### Mnemon (hypervisor group) fields

| Field | Required | Description |
| --- | --- | --- |
| `group` | yes | Human-readable label for the group (used in logs/plan output). |
| `host` | yes | Hypervisor address for the SSH/libvirt connection. |
| `user` | yes | SSH user on the hypervisor. |
| `port` | yes | SSH port (1–65535). |
| `key` | — | Path to the **private** SSH key on the machine running Mnemosyne. |
| `servers` | yes (≥1) | **Map** of VMs (`<id>: { …fields… }`) that should exist in this group. |

The next three settings are **group defaults**: declare them once on the Mnemon and every server
inherits them. Any server may override its own value.

| Field | Default | Description |
| --- | --- | --- |
| `volLookup` | `noble-server-cloudimg-amd64.img` | Base image inside the pool to clone new disks from. |
| `metaUrl` | `http://127.0.0.1:80/files/` | Base URL where VMs reach the cloud-init server. Set it to `http://<mnemosyne-host>:8080/cloud-init/`. |
| `templates` | see [below](#cloud-init-templates) | Block of template paths (`serverTmpl`, `volTmpl`, `userDataTmpl`, `networkConfigTmpl`). |

### Server (VM) fields

Each entry under `servers` is keyed by its **id**. The id is stored in the domain's libvirt metadata
and is how Mnemosyne recognizes the VM on later runs — keep it stable, and rename the VM freely via
`name`.

| Field | Required | Default | Description |
| --- | --- | --- | --- |
| *(map key)* | yes | — | The server **id** — stable identity stored in libvirt metadata. |
| `name` | — | *(the id)* | VM name. Used as libvirt domain name, disk volume name **and** hostname. Defaults to the map key. |
| `cpu` | yes | `2` | vCPU count (positive integer, as a string). |
| `ram` | yes | `1024` | RAM in **MiB** (positive integer, as a string). |
| `ip` | yes | — | Address in **CIDR** notation, e.g. `192.168.70.70/24`. |
| `gateway` | — | — | Default gateway (plain IPv4). |
| `disk` | — | `30` | Disk size in **GiB** (min 10). The cloned image is resized to this. |
| `pool` | yes | `default` | libvirt storage pool that holds the base image and the new disk. |
| `network` | yes | `default` | libvirt network / bridge name to attach the VM to. |
| `launch` | — | `true` | Whether the VM should be started. |
| `volLookup` | inherited | *(group value)* | Override the group's base image for this VM only. |
| `metaUrl` | inherited | *(group value)* | Override the group's cloud-init base URL for this VM only. |
| `templates` | inherited | *(group value)* | Override individual template paths for this VM only (merged over the group block). |

> **Important:** `metaUrl` must be reachable **from inside the VM**. `127.0.0.1` only works if the
> guest and Mnemosyne share the network namespace; normally you want the hypervisor-reachable IP of
> the host running Mnemosyne plus port `8080` and path `/cloud-init/`.

---

## cloud-init templates

When a VM is created, Mnemosyne renders two cloud-init documents from templates and serves them
over HTTP:

- **user-data** — packages, users, SSH keys, sysctl, etc. `hostname`/`fqdn` are filled from the
  server `name`. See [`templates/user-data.example.yml`](templates/user-data.example.yml).
- **network-config** — the `vif0` interface gets its `addresses` and `gateway4` filled from the
  server's `ip`/`gateway`. See [`templates/network-config.example.yml`](templates/network-config.example.yml).

The `.example.yml` files are committed as reference. Copy them to the working names and add your
own SSH public keys:

```bash
cp templates/user-data.example.yml      templates/user-data.yml
cp templates/network-config.example.yml templates/network-config.yml
# then edit templates/user-data.yml — replace the placeholder ssh_authorized_keys with your keys
```

Template paths are configured through the `templates` block — set once per group and inherited by
every VM, or overridden per server. Each path defaults to `/app/templates/<file>` (the location they
are baked into in the Docker image):

```yaml
templates:
  serverTmpl:        /app/templates/server.xml             # domain XML
  volTmpl:           /app/templates/volume.xml             # volume XML
  userDataTmpl:      /app/templates/user-data.yml          # cloud-init user-data
  networkConfigTmpl: /app/templates/network-config.yml     # cloud-init network-config
```

---

## Example output

```
10:42:07 Connection to 'hv01.example.lan' was successful.

--- Plan ---------------------------------------------------
[ hv01.example.lan ]  delete: 1, update: 1, create: 1
  - old-test.example.lan
  ~ cache-01  (cpu 2->4)
  + web-01

[ hv02.example.lan ]  no changes

Applying in 10s — Ctrl+C to abort...

--- Applied ------------------------------------------------
[ hv01.example.lan ]  delete: 1, update: 1, create: 1
  - old-test.example.lan
  ~ cache-01  (cpu 2->4, applies after restart)
  + web-01

10:42:21 All 1 mnemones provisioned. Waiting cloud-init is done...
10:43:35 All cloud-init tasks completed (1/1). Preparing for shutdown...
```

`Plan` and `Applied` share one format, so the two blocks line up entry by entry. Anything that
failed keeps the run going and is listed under `skipped`:

```
--- Applied ------------------------------------------------
[ hv01.example.lan ]  delete: 1, create: 1, skipped: 1
  - old-test.example.lan
  + web-01
  · cache-01  (update failed (see log))
```

With `--join`, the plan instead lists the unmanaged domains that can be adopted:

```
--- Plan ---------------------------------------------------
[ hv01.example.lan ]  adopt: 1, unmanaged: 1
  + legacy-web.example.lan  (as 'web-01')
  > legacy-db.example.lan

--- Applied ------------------------------------------------
[ hv01.example.lan ]  join: 1
  + web-01
```

---

## Project layout

```
src/main/java/com/mnemosyne/app/
  Mnemosyne.java                 # entry point & orchestration (the run loop above)
  config/Config.java             # CLI argument parsing (--servers-file, --plan, --join)
  model/Mnemon.java              # hypervisor group: connect, plan, apply (delete/update/create), join
  model/Server.java              # one VM: XML/YAML template rendering, validation
  model/Templates.java           # template paths: group defaults + per-server overrides
  model/Plan.java                # create / update / delete / unmanaged diff
  output/Report.java             # shared plan/apply block printed to the console
  model/Status.java              # per-server lifecycle status
  model/DomainState.java         # snapshot of a live domain (id, cpu, ram, managedBy)
  model/DomainInspector.java     # read metadata & disk paths back from existing domain XML
  utils/Sha256Util.java          # spec-hash helper for drift detection
  http/CloudInitServer.java      # built-in HTTP server for cloud-init data
templates/                       # domain/volume XML + cloud-init YAML templates
configs/                         # inventory + logback config
```

---

## License

Apache License 2.0 — see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).
