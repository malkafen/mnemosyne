# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
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
