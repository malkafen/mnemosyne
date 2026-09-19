# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- `extraDisks` gives a VM additional blank disks: a list of `name`, `size` and an optional
  `pool` that defaults to the VM's own. They are created as sparse qcow2 volumes named
  `<vm>-<disk>.qcow2` and attached after the root disk, and they reach the guest raw —
  partitioning and filesystems stay with the administrator. Each disk's name is written into
  the domain's `<serial>`, so the guest gets a stable `/dev/disk/by-id/virtio-<name>` that
  does not move when the target letters shift. Target names continue from the domain
  template's own disks, so a template with a cdrom or a second disk cannot collide.
  An extra disk is a copy of the template's root `<disk>`, minus `<boot>`, `<address>` and
  `<backingStore>`, so the bus and the `discard`/`cache`/`io` tuning are stated once.
- An existing disk is recognised by its `<serial>` first and by its volume file name only as a
  fallback, so renaming a VM through `name:` does not make its data disks look missing and earn it
  a second, empty set of them beside the originals.
- Disks are reconciled on every run, add-only: one the inventory lists and the domain lacks is
  created and attached, to an existing VM as well as to a new one. On a running guest it is
  hot-plugged; when the hypervisor or guest cannot — most often because libvirt has no spare
  hot-pluggable PCIe port left — it is written to the persistent config anyway and reported as
  `applies after power cycle`, so the run still converges on the domain's next start. A reboot
  from inside the guest is not enough: it keeps the same QEMU process and the same devices.
  Nothing is ever detached, resized or deleted while the VM exists: a disk missing from the
  inventory, a size that no longer matches and a changed `pool` are all reported as `note`
  lines and left alone. A volume that already exists under the expected name is reused rather
  than replaced, reported as `reused existing volume`. Deleting the VM still deletes all of
  its volumes, and the plan names every one of them first.
- Preflight refuses a volume that is already attached to another domain, comparing full paths so
  two pools holding a same-named volume are not confused, and refuses two servers of one group
  that would create the same volume. Two domains backed by one qcow2 corrupt it before anything
  reports an error, so this blocks the run instead of being reported as a note. The checks now
  cover the pools of updates that add a disk, not only creations.
- Every run now checks what the planned creations need from the host before applying anything:
  the storage pool, the base image `volLookup` inside it, the libvirt network, and the readability
  of the template files. Problems are printed under `Preflight` and block the entire run — including
  `--plan`, which exits non-zero — so a batch no longer fails halfway through with some VMs created
  and the rest skipped. The checks are read-only and run over the connection the plan already uses:
  one lookup per distinct pool, image and network, none at all for a group with nothing to create,
  and a pool refresh only when the image is missing from libvirt's cache.
- `autostart: true|false` is an optional per-VM flag mapped to libvirt's autostart.
  Left out, the domain's own setting is neither read as drift nor changed.

### Changed
- `launch` is now reconciled on every run instead of only at creation: a managed domain
  that is shut off while the inventory says `launch: true` is started, and one that runs
  while the inventory says `launch: false` is asked to shut down and destroyed if it is
  still up after 60 seconds. Power and autostart drift are reported as ordinary `update`
  entries, so the plan gains no new category.
- A new VM now boots once regardless of `launch`, so cloud-init always configures it; one
  with `launch: false` is shut down at the end of the same run, reported under `Settled`.
  Starting an existing VM therefore needs no cloud-init seed and is never awaited.
- `applies after restart` is printed only when the domain keeps running; a VM that is shut
  down in the same pass picks up its new vCPU/RAM on the next boot anyway.

## [0.2.0] - 2026-09-16

### Added
- RAM is now reconciled: a managed domain whose memory differs from the inventory
  is redefined in place, alongside the existing vCPU update. Both take effect on the
  guest's next boot.
- The plan lists the file-backed volumes that will be deleted along with each VM, so
  a `delete` entry shows exactly which disks go with it.
- CI: `spotless:check` and `clean package` run as separate jobs on push to `main` and
  on every pull request, and the shaded jar is uploaded as a build artifact.
  google-java-format is pinned to 1.22.0 in the POM instead of living in the README.

### Changed
- Completion of a new guest is tracked per server through cloud-init's `phone_home`
  instead of counting seed requests. A timeout now names the servers that never
  reported and says whether they fetched their seed at all.
- The cloud-init server handles requests on a thread pool rather than one at a time.
- The cloud-init server starts only when applying; `--plan` no longer binds port 8080.
- `logback.xml` ships inside the jar, so the Docker image no longer copies a config
  file or passes `-Dlogback.configurationFile`.
- README rewritten against the current code: state model, run sequence, flags,
  inventory reference and output samples.

### Fixed
- An invalid inventory fails with the offending field path and message instead of a
  stack trace.
- A cloud-init seed is no longer registered for servers with `launch: false`, which
  previously made the run wait for a VM that was never going to boot.

## [0.1.2] - 2026-08-04

Initial tracked release.

[0.2.0]: https://github.com/malkafen/mnemosyne/compare/v0.1.2...v0.2.0
[0.1.2]: https://github.com/malkafen/mnemosyne/releases/tag/v0.1.2
