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

## 1.0.0.11 (2026-06-23)

- fix(adapter): **remove the resurrected SCSI `Config|` (pipe) key dual-emit.**
  Build 3 (`a5ef1c2`) added a second emit of the SCSI controller info under
  `vCommunity|Config|SCSI Controllers|{bus}|Type` + `…|Count` alongside the
  canonical `Configuration|SCSI Controllers:{bus}|Type` + `…|Count`, with a
  comment claiming "like-for-like parity" with prod. The claim was false: the
  current upstream emits the pipe form **nowhere**. Upstream commit `d4633a6`
  (2025-11-20, Onur Yuzseven, "Virtual Machine SCSI Controller bug, fixes #39")
  migrated the pipe key to the colon-instanced `Configuration|…:{bus}|Type`,
  flipped Count from `with_property` to `with_metric`, and commented out the
  no-controller sentinel. The pipe key survives on prod only as a frozen ghost
  property from pre-Nov-2025 collection. Removed both pipe emits and replaced the
  misleading comment with an accurate note citing `d4633a6`. A full parity audit
  of every emitted key against current upstream found no other resurrected/retired
  keys, no other `Config` vs `Configuration` mismatch, and correct
  property-vs-metric and `:` vs `|` separators throughout.

## 1.0.0.10 (2026-06-22)

- feat(adapter): **surface the previously-swallowed guest-ops SOAP fault on the
  anchor** to identify why in-guest collection fails on devel. Build 9's anchor
  diagnostics confirmed the gate passes for the three Windows VMs, yet every
  in-guest call returns zero rows with *no* fault logged anywhere — because
  `GuestOpsClient.post()` discarded any non-2xx response silently
  (`if (code < 200 || code >= 300) return null;`), dropping the HTTP-500 + SOAP
  fault body without reading the `<faultstring>`. The collector returned empty,
  and the per-VM `catch` never fired (nothing threw), so the fault reached no
  log — including the appliance log. This mirrors
  `VCommunityVSphereClient.post()`'s existing faultstring extraction into
  `GuestOpsClient.post()`. **Observability-only** — `post()` still returns null
  to the caller, so collection behavior (auth, spec serialization,
  fileAttributes, the whole collection path) is byte-for-byte unchanged; we only
  capture and surface the fault so the exact wire-level fault comes back
  uncontaminated. No speculative credential/auth fix applied.
  - `Summary|guestops_last_error` — bounded per-failed-VM fault summary
    (`vm: <operation> -> <faultClass> (<message>); …`), where `<operation>` is
    the faulting guest call (CreateTemporaryDirectoryInGuest /
    InitiateFileTransferToGuest / StartProgramInGuest /
    InitiateFileTransferFromGuest) and `<faultClass> (<message>)` is the vim25
    `<faultstring>` plus, when present, the `<localizedMessage>` subtype. Capped
    at 5 detailed entries (overflow summarized as a count), same bounding pattern
    as `guestops_skips`, so the anchor never floods. **No credential material** —
    operation / fault-class / message only (never winUser/winPass), with a
    belt-and-braces token redactor mirroring the vSphere client. `"none"` when no
    VM faulted this cycle.
  - Each fault is also logged at WARN with the operation name and target VM.
  - Diagnostics only; once the fault names the precise root cause, the fix lands
    in a later build and this instrument can be pruned.

## 1.0.0.9 (2026-06-22)

- feat(adapter): **readable guest-ops decision diagnostics on the anchor**.
  Build 8 fixed one suspect (the per-VM gate's narrow vim25 read), but the
  evidence is split — the 2026-06-22 recon points at a *different* leg,
  `GuestOpsClient.ready()`, and the build-8 WARN-on-skip only lands in the
  appliance adapter log, which is 404 via the Suite API. This surfaces the
  guest-ops decision path as `Summary|*` string/count properties on the
  `vCommunityWorld` anchor so one install + one recon definitively tells us
  which leg blocks devel collection. **Behavior-neutral** — observes the
  existing decision path; collection (the strict `toolsOk`+`windowsGuest` gate,
  the `ready()` predicate) is unchanged.
  - `Summary|guestops_ready` — the `ready()` outcome this cycle and, if false,
    the exact failing precondition (e.g. `false (guestProcessManager=null)`), or
    why it was not evaluated (monitoring disabled / no Windows credential /
    guest-ops client unavailable). New `GuestOpsClient.readyReason()` reads the
    same predicate `ready()` evaluates, in the same order.
  - `Summary|guestops_vms` — per-cycle gate tally
    (`considered=N passed=N skipped=N`) over the Windows-candidate VMs that
    reached the per-VM gate.
  - `Summary|guestops_skips` — bounded per-VM skip summary with the actual gate
    values read (`vm[tools=…,family=…,guestId=…]; …`), capped at 10 detailed
    entries (overflow summarized as a count) so the anchor property never grows
    unbounded with VM count. New `VCommunityVSphereClient.vmGuestId()` reads
    `guest.guestId` off the same broad `guest` object, on the skip path only —
    the happy path's two reads are untouched.
  - Diagnostics only; pruned once the blocking leg is confirmed.

## 1.0.0.8 (2026-06-22)

- fix(adapter): repair the **silent Windows guest-ops gate-skip**. On devel,
  Windows guest-ops collected nothing (0 `vCommunity|Guest OS|Services:*` keys,
  0 event keys) while the adapter stayed GREEN with no error — same vCenter,
  VMs, and credential as the prod original, which *does* collect. Root cause:
  the per-VM gate (`toolsStatus == "toolsOk" AND guestFamily == "windowsGuest"`)
  read its inputs via a **narrow vim25 `RetrieveProperties` pathSet** requesting
  the sub-paths `guest.toolsStatus` / `guest.guestFamily` directly, which
  returned blank/stale, so every Windows VM was silently rejected. The prod
  original (pyVmomi `vmService.py:129-131`, `collect_windows_events.py:133-135`)
  reads the **full `guest` (GuestInfo)** object and gets populated values.
  - `VCommunityVSphereClient.vmGuestToolsStatus` / `vmGuestFamily` now retrieve
    the broad `guest` property (single `RetrieveProperties` of `guest`) and read
    `toolsStatus` / `guestFamily` off the returned object — matching the
    original's broad read. The gate predicate is **unchanged** (strict
    `toolsOk` + `windowsGuest`); we matched the read, not loosened the rule.
  - `VmCollector.collectGuest` now emits a **WARN** when a VM is skipped at the
    gate, logging the VM name and the actual `toolsStatus` / `guestFamily`
    values read. Behavior-neutral diagnostics only — does not change what is
    collected (adapter log is currently the only ground-truth window: API 404).

## 1.0.0.7 (2026-06-17)

- fix(content-emit): correct three content-emit format gaps the live devel
  install of 1.0.0.6 surfaced (builder/render fixes — no adapter Java change):
  - **Symptom operator** — symptomdef XML now emits the C-style symbol form
    (`!=` etc.) the platform importer accepts, not the REST enum name
    (`NOT_EQ`, which failed with `Invalid operator:not_eq`). Matches the
    original's symptomdef XML verbatim.
  - **Super-metric JSON** — each SM now carries `modificationTime` + `modifiedBy`
    (the importer's CREATE path calls `readLong(modificationTime)`; absent →
    `NumberFormatException`, so NEW SMs silently failed to create). Matches the
    original's SM JSON shape.
  - The 96th view (`Guest OS List of Services`, `APPLICATIONDISCOVERY` kind) is
    correctly skipped by the importer when Service Discovery is absent — not a
    defect; all 96 ship.

## 1.0.0.6 (2026-06-17)

- fix(adapter): rework the credential model, instance-config labels, and
  monitoring toggles to be **like-for-like with the prod original**
  (`VCFOperationsvCommunity` `app/adapter.py`). Three correctness/parity fixes:
  - **Single combined credential.** Collapsed the two credential kinds
    (`vcenter_credentials` + `windows_guest_credentials`) into ONE kind
    (`vsphere_user`, "vCenter Credential") with FOUR fields — `user`/`password`
    (required) + `winUser`/`winPass` (optional, password) — matching the
    original. An Ops adapter instance binds exactly one credential; the old
    two-kind shape left the Windows guest credential with no binding slot, so
    guest-ops could never receive a credential. The Java reads all four fields
    from the one bound credential (`getResourceCredential()`), unchanged.
  - **Two monitoring toggles instead of one enum.** Replaced the single
    `windows_monitoring` four-way enum with the original's TWO separate
    Enabled/Disabled enums — `serviceMonitoring` ("Guest OS Service Monitoring
    Status") and `winEventMonitoring` ("Windows Event Log Monitoring Status").
    `VCommunityConfig.WindowsMonitoring.from(svc, evt)` derives the services /
    event-log gates from the two booleans; only literal "Enabled" turns a gate
    on (Disabled/null/blank/garbage → off — never folds unreadable into on).
    Collection behaviour (the `services()` / `eventLogs()` gates) is preserved.
  - **Clean labels + (i) descriptions + clean adapter-kind display name.**
    Populated `resources.properties` with the original's verbatim field labels
    and `<nameKey>.description` help text ("vCenter Server", "ESXi Advanced
    System Settings Config File", etc.); renamed the host identifier
    `vcenter_host` → `host` to match the original's key. Fields no longer render
    as raw identifiers.
  - **Config contract change.** Existing devel instances must be reconfigured
    after the next install — their old two-credential / single-enum config does
    not map to the new single-credential / two-toggle surface. Expected.

## 1.0.0.5 (2026-06-16)

- feat(content): bundle the ported vCommunity content set into the pak — **37
  super metrics**, **96 views**, **12 dashboards**, **2 symptom definitions** —
  reverse-ported from the original `VCFOperationsvCommunity` MP (render-vs-source
  verified). SM cross-references resolve to `Super Metric|sm_<uuid>`; external
  /platform view references pass through by UUID. Listed in `bundled_content`.
  Reports, alerts, and the report-input dashboards land in a later build; the
  Windows/OS surface is Phase-3 (gated on a Windows guest credential).

## 1.0.0.4 (2026-06-16)

- fix(adapter): align the VMware-Tools **Guest OS** property key names to the
  prod original's six canonical `OS `-prefixed names so the pushed
  `vCommunity|Guest OS|Operating System|` keys are byte-identical to the
  original's Windows-CSV path: `Name`→`OS Name`, `Version`→`OS Version`,
  `BuildNumber`→`OS BuildNumber`, `Release ID`→`OS Release ID`, `Last Boot Up
  Time`→`OS Last Boot Up Time` (`OS Architecture` was already correct). Makes
  this tools-info path a benign non-Windows superset that reuses the original's
  exact key names, so ported content referencing `OS Last Boot Up Time` etc.
  finds data on every VM whose tools report it. Skip-if-absent behavior
  unchanged — each key pushed only when the guest reported it; never a sentinel
  (Phase 1 §1c, parity plan). The Windows guest-ops OS path is untouched
  (Phase 3).

## 1.0.0.3 (2026-06-16)

- feat(adapter): close HostSystem **Licensing** parity gap (10 keys). Resolve
  `licenseAssignmentManager` correctly via `licenseManager.licenseAssignmentManager`
  (build-2 read it off ServiceContent where it does not exist, so every host
  licensing key silently dropped on devel). Per assigned license now emits
  `vCommunity|Licensing:<name>|Name`/`License Key`/`License Expiration Date`/
  `Edition Key` (PROP) + `Remaining Days` (STAT). `<name>` is dynamic; no
  hardcoded license names. `Remaining Days` is skipped (never a sentinel) when
  expiration is absent/unparseable.
- feat(adapter): close VirtualMachine **Guest OS** parity gap (6 PROP keys) from
  VMware-Tools guest info (vim25 `guest.detailedData` + `runtime.bootTime`), so
  they populate on non-Windows guests too (no Windows credential / guest-ops
  required), matching prod verbatim: `vCommunity|Guest OS|Operating System|`
  `Name`/`OS Architecture`/`BuildNumber`/`Release ID`/`Version`/`Last Boot Up
  Time`. Each field is pushed only when the guest reports it — unreported =
  skipped, never a sentinel.
- feat(adapter): emit the legacy **`Config` SCSI alias** alongside the canonical
  `Configuration` path for like-for-like parity:
  `vCommunity|Config|SCSI Controllers|Count` (STAT) +
  `vCommunity|Config|SCSI Controllers|<bus>|Type` (PROP, pipe-delimited index).
- fix(adapter): F2 diagnosability. A total collect failure (vCenter
  unreachable / DNS NXDOMAIN / refused / timeout) now throws an actionable,
  secret-free message and maps to `RESOURCE_STATUS_DOWN`, so the instance turns
  red instead of a silent DATA_RECEIVING-with-0-metrics cycle (the NXDOMAIN
  episode). `VCommunityVSphereClient.post()` now parses and surfaces the SOAP
  `<faultstring>`/`<localizedMessage>` on non-2xx (REDACTING session-id /
  password tokens per `rules/no-secrets-on-disk.md`) instead of discarding the
  fault body, so login/connection failures are diagnosable; the Login failure
  message carries the faultstring.
- fix(adapter): harden the F2 SOAP-fault `redactSecrets` backstop to cover the
  full DEF-001 secret-in-path token family — additionally strip `_sid`,
  `passwd`, and `account` tokens (`(?i)(_sid|passwd|account)\s*[=:]\s*\S+`)
  alongside the existing `vmware_soap_session`/`password`. Defense-in-depth per
  `rules/no-secrets-on-disk.md`; not reachable on the current path (only
  server-authored fault responses are surfaced, never the client request), but
  keeps the redactor's coverage in lock-step with the lesson so a future
  request-echoing code path can't leak. Build-3 review WARNING-1.

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
