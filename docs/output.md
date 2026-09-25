# Output

[← README](../README.md)

Plan and applied blocks share one format, so they line up entry by entry. Every group gets a
header with counts, then one line per VM, with indented detail lines below it.

| Marker | Meaning |
| --- | --- |
| `+` | create (plan / applied), adopt or join (`--join`) |
| `~` | update |
| `-` | delete; under `Settled`, a VM shut down after cloud-init |
| `!` | blocked, or pending — nothing is applied for it |
| `i` | note: seen, deliberately left alone |
| `>` | unmanaged domain (`--join`) |
| `·` | failed and skipped; the run continues and exits `1` |

## Plan and Applied

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

## Notes

Disk facts Mnemosyne will not act on. A VM with nothing but notes is counted separately, so
`note: 1` never reads as a change:

```
--- Plan ---------------------------------------------
[ hv01.example.lan ]  note: 1
  i db-01
      disk 'vdc' (db-01-scratch.qcow2) is not in the inventory - left as is
```

## Blocked

A prerequisite the host does not have, a disk that would shrink or a missing init marker. The run
stops before the confirmation window — no VM is touched, in any group
([preflight](how-it-works.md#preflight)):

```
--- Plan ---------------------------------------------
[ hv01.example.lan ]  delete: 1, blocked: 1
  - old-test.example.lan
      /var/lib/libvirt/images/old-test.example.lan
  ! web-01  (network 'host-bridg' not found on the host)

Nothing was applied: 1 of 2 group(s) cannot be created as planned.
```

## Pending

A VM whose cloud-init never confirmed it finished ([initialization marker](cloud-init.md#initialization-marker)).
It is left alone and the run exits `1`:

```
--- Plan ---------------------------------------------
[ hv01.example.lan ]  pending: 1
  ! web-01  (init pending since 2026-09-25T10:00:03Z: cloud-init never confirmed it finished - left as is)
```

## Settled

VMs created with `launch: false` boot once for cloud-init and are shut down when it is done:

```
--- Settled -------------------------------------------
[ hv01.example.lan ]  stop: 1
  - standby-01  (initialized)
```

A guest that never reported back is shut down all the same, but not called initialized, and the run
exits `1`:

```
--- Settled -------------------------------------------
[ hv01.example.lan ]  stop: 1
  - standby-01  (cloud-init did not finish)

Run finished incomplete: cloud-init did not finish on 1 server(s): standby-01.
```

## Join

With `--join` the plan lists the unmanaged domains, marking the ones that can be adopted:

```
--- Plan ---------------------------------------------
[ hv01.example.lan ]  adopt: 1, unmanaged: 1
  + legacy-web.example.lan  (as 'web-01')
  > legacy-db.example.lan

--- Applied -------------------------------------------
[ hv01.example.lan ]  join: 1
  + web-01
```
