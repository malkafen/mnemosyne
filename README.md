# Mnemosyne

Declarative provisioning of libvirt/KVM virtual machines. One YAML inventory describes the VMs
that should exist on a set of hypervisors; every run reads what actually exists, prints the
difference and applies it.

## What it does

- **Plans before it acts.** Each run diffs the [inventory](docs/configuration.md) against the live
  domains and prints `create` / `update` / `delete`, then checks that the host has every pool,
  base image and network the plan needs. One missing prerequisite blocks the whole run before
  anything is touched — see [how a run works](docs/how-it-works.md).
- **Touches only its own VMs.** Managed domains carry `mnemosyne` metadata and a stable
  `serverId`, so a VM can be renamed without being recreated. Pre-existing domains are never
  touched unless [adopted with `--join`](docs/how-it-works.md#adopting-existing-vms).
- **Configures guests with cloud-init.** A built-in HTTP server hands each new VM its
  `meta-data`, `user-data` and `network-config` over the NoCloud datasource and waits for the
  guest's `phone_home`. What is rendered from where, and how completion is recorded, is in
  [cloud-init](docs/cloud-init.md).
- **Reconciles vCPU, RAM, power state and autostart** of existing VMs on every run.
- **Manages data disks, add-only.** `extraDisks` are created, attached and grown, but never
  detached, shrunk or deleted while the VM exists — see [disks](docs/disks.md).
- **Remembers unfinished work.** A VM whose cloud-init never finished stays `pending` instead of
  quietly becoming "no changes" ([initialization](docs/cloud-init.md#initialization-marker)).

## Quick start

> Prebuilt releases are published too: the jar on
> [Releases](https://github.com/malkafen/mnemosyne/releases) and the Docker image
> [`ghcr.io/malkafen/mnemosyne:latest`](https://github.com/malkafen/mnemosyne/pkgs/container/mnemosyne)
> — use them instead of building from source.

Requirements — here: Java 17+, Maven, the libvirt client libraries (`libvirt0`,
`libvirt-clients`, `libvirt-dev`) and an SSH key the hypervisors accept. On each hypervisor:
libvirt/KVM reachable over SSH, a storage pool holding a base cloud image (e.g. a Debian
`genericcloud` qcow2) and a libvirt network the guests can reach this machine's port `8080` from.

```bash
# 1. Build
mvn clean package                                  # target/mnemosyne-<version>.jar

# 2. Describe your VMs
cp configs/servers.example.yml configs/servers.yml # every field is documented inside
$EDITOR configs/servers.yml
$EDITOR templates/user-data.yml                    # put your SSH public key here

# 3. Preview — nothing is touched
java -jar target/mnemosyne-*.jar -f configs/servers.yml --plan

# 4. Apply (10-second window to Ctrl+C)
java -Djna.library.path=/usr/lib/x86_64-linux-gnu \
     -jar target/mnemosyne-*.jar -f configs/servers.yml
```

Templates are read from `/app/templates/` by default, which is where the Docker image keeps them.
Running the jar directly, either copy `templates/` there or point the inventory's `templates:`
block at your checkout ([templates](docs/configuration.md#templates)). `-Djna.library.path` is
`/usr/lib64` on RHEL/Fedora and `/opt/homebrew/lib` on macOS.

Or with Docker, which ships the libraries and templates:

```bash
docker build -t mnemosyne .
docker run --rm --network host \
  -v "$PWD/configs:/app/configs" \
  -v "$HOME/.ssh:/root/.ssh:ro" \
  mnemosyne -f /app/configs/servers.yml --plan
```

A plan looks like this ([more output examples](docs/output.md)):

```
--- Plan ---------------------------------------------
[ hv01.example.lan ]  delete: 1, update: 1, create: 1
  - old-test.example.lan
      /var/lib/libvirt/images/old-test.example.lan.qcow2
  ~ cache-01  (cpu 2->4, attach 'data' 40G as vdb)
  + web-01
      web-01-data.qcow2 40G in pool 'default'
```

## Documentation

| Document | What is in it |
| --- | --- |
| [How it works](docs/how-it-works.md) | Architecture, the run step by step, state model, plan, preflight, `--join`, parallelism |
| [Cloud-init](docs/cloud-init.md) | Seed delivery, which template fields are filled from the inventory, `phone_home`, the `<mnem:init>` marker |
| [Configuration](docs/configuration.md) | Inventory format, command-line flags, exit codes, templates |
| [Disks](docs/disks.md) | Root and extra disks: naming, attach, grow, what is never done, deletion |
| [Output](docs/output.md) | Plan, Applied, Settled blocks and what every marker means |
| [CHANGELOG](CHANGELOG.md) | What changed between versions |

## Development

```bash
mvn spotless:apply    # google-java-format; CI runs spotless:check and rejects unformatted code
mvn test              # unit tests, libvirt mocked
mvn verify            # + *IT: the reconciler against libvirt's in-memory test driver
```

`mvn verify` needs only the libvirt client library (`libvirt0`): the integration tests open
`test:///` connections to [`src/test/resources/libvirt/test-node.xml`](src/test/resources/libvirt/test-node.xml),
so no daemon, KVM or hypervisor is involved. The test driver cannot resize a volume or a block
device and cannot attach a disk to the persistent config, so growing and adding disks stay covered
by the unit tests only. On macOS, `brew install libvirt` is enough: a Maven profile points JNA at
Homebrew's `lib`. Without the library the integration tests are skipped locally and fail in CI.

## License

Apache License 2.0 — see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).
