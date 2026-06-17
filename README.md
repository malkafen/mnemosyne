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
            ┌──────────────────────────── Mnemosyne (your workstation / CI) ────────────────────────────┐
            │                                                                                            │
servers.yml ─▶ load + validate ─▶ qemu+ssh ─▶ plan (diff) ─▶ create storage ─▶ define & start domains   │
            │                         │            │                                     │               │
            │                         ▼            ▼                                     ▼               │
            │                  libvirt host   create / delete                    CloudInitServer        │
            │                  (KVM/QEMU)        decision                        HTTP :8080              │
            └──────────────────────────────────────────────────────────────────────┬─────────────────┘
                                                                                     │ ds=nocloud
                                                                                     ▼
                                                                          new VM fetches user-data,
                                                                          meta-data, network-config
```

The run is a single pass (mirrors `Mnemosyne.run`):

1. **Load & validate the desired state** — parse the inventory into a `List<Mnemon>` and validate
   every field with Jakarta Bean Validation. Any invalid field aborts the run with a precise
   message.
2. **Start the cloud-init server** — a built-in HTTP server starts on port **8080** under the
   `/cloud-init` path.
3. **Connect** — for each hypervisor group, open a libvirt connection over
   `qemu+ssh://user@host:port/system?keyfile=…`.
4. **Plan** — compare the **desired state** (inventory) against the **current state** (domains that
   already exist on the host) and print a Terraform-style diff (`+ create`, `- delete`). With
   `--plan` the run stops here.
5. **Confirmation window** — a 10-second pause (`Ctrl+C` to abort) before any change is applied.
6. **Provision storage** — for each new VM, clone its disk from a base cloud image
   (`volLookup`) inside the target storage pool and resize it to the requested capacity. Failures
   roll back the partially created volume.
7. **Define & start domains** — build the domain XML (name, RAM, vCPU, disk, network, and the
   cloud-init NoCloud serial), register the VM's `user-data` / `network-config` with the HTTP
   server, define the domain and boot it.
8. **Wait for cloud-init** — poll until every VM has pulled its config (or a ~100 s timeout:
   20 attempts × 5 s).
9. **Reconcile** — destroy, undefine and delete the disks of domains that are present on the host
   but absent from the inventory.
10. **Shut down** — free domain handles, close libvirt connections, stop the HTTP server.

cloud-init wiring uses the **NoCloud** datasource: each VM is given the SMBIOS serial
`ds=nocloud;s=<metaUrl><name>/`, which tells cloud-init inside the guest where to fetch its
`meta-data`, `user-data` and `network-config`. Those requests land on Mnemosyne's HTTP server,
which answers from the per-VM data registered in step 7.

A rendered activity diagram lives in [`diagrams/`](diagrams/) (`Mnemosyne Activity Diagram.png`).

---

## Concepts & terminology

| Term | Meaning |
| --- | --- |
| **Mnemon** | One **hypervisor group**: a libvirt host plus the list of VMs that should live on it. Top-level item in the inventory. |
| **Server** | One **virtual machine** (a libvirt *domain*) belonging to a Mnemon. |
| **Plan** | The create/delete diff between the desired state (inventory) and the current state (existing domains). |
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

The inventory is a YAML **list of Mnemons** (hypervisor groups). A fully commented starter file is
provided at [`configs/servers.example.yml`](configs/servers.example.yml) — copy it and edit:

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
| `servers` | yes (≥1) | List of VMs that should exist in this group. |

### Server (VM) fields

| Field | Required | Default | Description |
| --- | --- | --- | --- |
| `name` | yes | — | VM name. Used as libvirt domain name, disk volume name **and** hostname. |
| `cpu` | yes | `2` | vCPU count (positive integer, as a string). |
| `ram` | yes | `1024` | RAM in **MiB** (positive integer, as a string). |
| `ip` | yes | — | Address in **CIDR** notation, e.g. `192.168.70.70/24`. |
| `gateway` | — | — | Default gateway (plain IPv4). |
| `disk` | — | `30` | Disk size in **GiB** (min 10). The cloned image is resized to this. |
| `pool` | yes | `default` | libvirt storage pool that holds the base image and the new disk. |
| `volLookup` | — | `noble-server-cloudimg-amd64.img` | Base image inside `pool` to clone the disk from. |
| `network` | yes | `default` | libvirt network / bridge name to attach the VM to. |
| `metaUrl` | yes | `http://127.0.0.1:80/files/` | Base URL where the VM reaches Mnemosyne's cloud-init server. Set it to `http://<mnemosyne-host>:8080/cloud-init/`. |
| `launch` | — | `true` | Whether the VM should be started. |
| `serverTmpl` | — | `/app/templates/server.xml` | Domain XML template. |
| `volTmpl` | — | `/app/templates/volume.xml` | Volume XML template. |
| `userDataTmpl` | — | `/app/templates/user-data.yml` | cloud-init user-data template. |
| `networkConfigTmpl` | — | `/app/templates/network-config.yml` | cloud-init network-config template. |

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

---

## Example output

```
[ hv01.example.lan ]  create: 2, delete: 1
  + web-01.example.lan
  + cache-01.example.lan
  - old-test.example.lan

Applying in 10s — Ctrl+C to abort...
10:42:07 Connection to 'hv01.example.lan' was successful.
10:42:19 Domain 'web-01.example.lan' has been started successfully.
10:42:21 Domain 'cache-01.example.lan' has been started successfully.
10:42:21 All 2 mnemones provisioned. Waiting cloud-init is done...
10:43:35 All cloud-init tasks completed (2/2). Preparing for shutdown...
```

---

## Project layout

```
src/main/java/com/mnemosyne/app/
  Mnemosyne.java                 # entry point & orchestration (the run loop above)
  config/Config.java             # CLI argument parsing
  model/Mnemon.java              # hypervisor group: connect, plan, storage, domains, reconcile
  model/Server.java              # one VM: XML/YAML template rendering, validation
  model/Plan.java                # create/delete diff
  model/Status.java              # per-server lifecycle status
  model/DomainInspector.java     # read disk paths back from existing domain XML
  http/CloudInitServer.java      # built-in HTTP server for cloud-init data
templates/                       # domain/volume XML + cloud-init YAML templates
configs/                         # inventory + logback config
diagrams/                        # activity & sequence diagrams
```

---

## License

Apache License 2.0 — see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).
