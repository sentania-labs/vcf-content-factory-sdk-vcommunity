# Changelog

## Known gaps (v1)

Shipped intentionally with documented gaps — none is a silent degradation. Full
detail in `README.md` ("Known gaps & roadmap").

- **TOOLSET GAP #1 — foreign-resource event push.** Windows event logs + host
  install-date read failures ship as alertable properties, not real events
  (Suite API facade has no event endpoint). Real events → **v1.1**.
- **NIT 1 — positional `Last Event` keys** (`…|Last Event|{n}|…`) can churn
  ordering cycle-to-cycle. Stable event-id keys → **v1.1** (folds in with #1).
- **Guest-ops scope/concurrency** — single-threaded, 120 s per-VM poll ceiling,
  all-Windows-VMs-in-scope when enabled. Per-VM scoping → **v2**.
- **EMPIRICAL-VERIFY at install** — unverified against live appliance: dual
  credential dialog renders + accepts unset Windows credential; vim25 surfaces
  beyond the compliance set (GuestOperationsManager, `QueryAssignedLicenses`,
  `fetchSoftwarePackages`/`installDate`, `EvcManager`); Suite API config fetch
  from a remote collector / cloud proxy.

## 1.0.0.2 (2026-06-10)

- fix(adapter): vCenter-scope foreign-resource resolution (the MOID-trap fix —
  build 2). `VCommunityStitcher` now pins the owning vCenter Instance UUID
  (`setOwningVcUuid`, from the live SOAP session) and drops any loaded VMWARE
  resource whose `VMEntityVCID` belongs to a *different* vCenter, so a bare MOID
  (`host-12`, not unique across vCenters) can no longer resolve onto a same-MOID
  host/VM/cluster in another vCenter in a multi-vCenter VCF Ops. Restores the
  scoping the original Python had via `adapterInstanceId` (`collectHostData.py:40`)
  that the compliance-reference matcher idiom had dropped. Resources with no
  `VMEntityVCID`, or when the owning UUID is unknown, degrade to the prior
  unscoped behaviour (single-vCenter deployments unaffected). Raised by
  `sdk-adapter-reviewer` (vcommunity build 1, WARNING). Cross-adapter note: the
  same fix is owed to the compliance adapter + a codified lesson — tracked by the
  orchestrator, out of scope for this adapter's repo.

## 1.0.0.1 (2026-06-10)

- feat(adapter): initial vCommunity Tier 2 Java SDK adapter (build 1) — native
  rewrite of `vmbro/VCF-Operations-vCommunity` (Onur Yuzseven, CC-licensed),
  killing the Python Integration SDK / Docker runtime. Adapter kind
  `vcfcf_vcommunity` (side-by-side fork; not an in-place upgrade of the
  original). Implements:
  - **Pure ARIA_OPS stitching.** One INTERNAL `vCommunityWorld` collection
    anchor; every `vCommunity|...` property/metric pushed onto existing foreign
    VMWARE `ClusterComputeResource` / `HostSystem` / `VirtualMachine` resources
    via the proven `SuiteApiStitcher` (`pushProperties` + `pushStats`). MoID-first
    identity match. No VMWARE topology edits.
  - **vim25 raw-SOAP client** (`VCommunityVSphereClient`) — hand-built SOAP 1.1
    over `HttpURLConnection` + JDK DOM, reflection-tolerant (walk by local-name,
    type discrimination by `xsi:type`, never a concrete-type cast). Cluster
    HA/DRS/EVC, host advanced settings / VIB packages / install date / licensing
    (+ Remaining Days) / NIC uplinks, VM options / extra-config / SCSI
    controllers / recursive snapshot count.
  - **Central config-file store** (`SolutionConfigStore`) — fetches the six
    `SolutionConfig/<name>.xml` check-lists by name via the SDK-injected Suite
    API channel each cycle (pure rewrite of the original `get_config_file_data`),
    with last-good caching; never silently collects with empty lists, surfaces
    fetch failures via WARN + the `vCommunityWorld` `config_file_status`
    property. Six byte-identical default XMLs ship in
    `content/files/solutionconfig/`.
  - **Windows guest-ops** (`GuestOpsClient`) — services / OS info / event logs
    via vim25 GuestOperationsManager (file transfer + run-in-guest + CSV read),
    gated by the `Windows Monitoring` enum (Disabled | Services | Event Logs |
    Services + Event Logs) and the optional second `Windows Guest Credential`
    kind. Per-VM crash-the-cycle isolation at two layers: one unreachable or
    mis-credentialed guest never aborts the cycle or other collectors.
- fix(framework): foreign-resource EVENT push is **TOOLSET GAP #1** — the Suite
  API facade exposes only `pushProperties`/`pushStats`, no event endpoint. Per
  the accepted staged plan, Windows event-log findings and host install-date
  read failures are degraded to **alertable properties**
  (`vCommunity|Guest OS|Last Event|...`, `vCommunity|Configuration|Install
  Date|Read Error`) this release — never silently dropped. Real foreign-resource
  events are a v1.1 deliverable once the push path is proven.
- docs: README migration runbook (uninstall original → install `vcfcf_vcommunity`
  → recreate instances/credentials); REFERENCE.md regenerated from describe.xml.
- note: the ~100-artifact bundled-content port (super metrics / dashboards /
  reports / views / symptoms / alerts) is a separate later workstream — not in
  this build.
