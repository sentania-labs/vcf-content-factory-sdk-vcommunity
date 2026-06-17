# VCF Content Factory vCommunity — Reference

Native Java SDK rewrite of `vmbro/VCF-Operations-vCommunity` (Onur Yuzseven,
CC-licensed). Adapter kind `vcfcf_vcommunity`.

## Adapter

| Field | Value |
|---|---|
| Adapter Kind | `vcfcf_vcommunity` |
| Tier | 2 (Java SDK) |
| Monitoring Interval | 5 minutes |
| License Required | No |

### Credentials (ONE combined credential kind)

**vCenter Credential** (`vsphere_user`, required) — mirrors the prod original's
single `vsphere_user` credential type. An Ops adapter instance binds exactly ONE
credential, so the vCenter and Windows guest credentials live in the SAME kind.

| Field | Key | Type | Required |
|---|---|---|---|
| vCenter User Name | `user` | string | Yes |
| vCenter Password | `password` | string (masked) | Yes |
| Windows User Name | `winUser` | string | No |
| Windows Password | `winPass` | string (masked) | No |

The type=7 adapter instance binds this single kind via
`credentialKind="vsphere_user"`. Guest-ops runs only when the optional Windows
fields are populated (`VCommunityConfig.hasWindowsCredential()`).

### Connection Settings

| Field | Key | Default | Required |
|---|---|---|---|
| vCenter Server | `host` | — | Yes |
| ESXi Advanced System Settings Config File | `esxi_adv_settings_config_file` | esxi_advanced_system_settings | No |
| ESXi Software Packages Config File | `esxi_vib_driver_config_file` | esxi_packages | No |
| VM Advanced Parameters Config File | `vm_adv_settings_config_file` | vm_advanced_parameters | No |
| VM Options Config File | `vm_configuration_config_file` | vm_options | No |
| Port | `port` | 443 | No |
| Windows Service Configuration File | `win_service_config_file` | windows_service_list | No |
| Windows Event Log Configuration File | `win_event_config_file` | windows_event_list | No |
| Guest OS Service Monitoring Status | `serviceMonitoring` | Disabled | No |
| Windows Event Log Monitoring Status | `winEventMonitoring` | Disabled | No |
| Allow Insecure SSL | `allowInsecure` | false | No |

`serviceMonitoring` and `winEventMonitoring` are independent `Enabled`/`Disabled`
toggles (matching the original's two Advanced-Settings enums); the services and
event-log collection gates derive from the two booleans. The six `*_config_file`
fields hold the NAME (no path,
no `.xml`) of a check-list file in the VCF Ops central configuration-file store
under `SolutionConfig/`. The bundled defaults ship in
`content/files/solutionconfig/` and import into the central store at install;
everything in them is commented out by design, so each gated collector emits
nothing until an admin uncomments entries centrally.

---

## Declared Object Type

### vCommunity World (`vCommunityWorld`, type=1)

Synthetic INTERNAL collection anchor (operability only). **Identifier**:
`world_id`.

#### Summary

| Key | Type | Meaning |
|---|---|---|
| `clusters_stitched` | metric | clusters matched + pushed this cycle |
| `hosts_stitched` | metric | hosts matched + pushed this cycle |
| `vms_stitched` | metric | VMs matched + pushed this cycle |
| `guest_vms_attempted` | metric | Windows guests guest-ops was attempted on |
| `guest_vms_degraded` | metric | guests where ≥1 guest-ops collector returned no data |
| `events_as_properties` | metric | Windows events surfaced as properties (TOOLSET GAP #1) |
| `last_scan_timestamp` | property | ISO timestamp of the last cycle |
| `config_file_status` | property | per-file fetched/parsed check counts + degradation notices |
| `status` | property | `OK` / stitcher-unavailable notice |

---

## Foreign-resource keys (pushed via Suite API, NOT in describe.xml)

Every key below is pushed onto the *existing* VMWARE resource (owned by the
VMWARE adapter) — they are intentionally not declared here, exactly as the
compliance adapter pushes onto VMWARE HostSystem. The namespace is `vCommunity|`
verbatim from the original (RULE-002; every key traced to a source line in
`designs/managementpacks/vcommunity-sdk.md`).

### ClusterComputeResource

`vCommunity|Cluster Configuration|vSphere HA|{Host Monitoring, Response \ Host
Isolation, Response \ Default VM Restart Priority, Response \ Datastore APD,
Response \ Datastore PDL, VM Monitoring, Heartbeat Datastore}` (properties; push
`"null"` when HA disabled) · `…|DRS|{Proactive DRS, Scale Descendants Shares,
CPU Over-Commitment}` (properties) · `…|DRS|DRS Score` (metric; 0 when DRS
disabled) · `…|EVC|{Enabled, Mode}` (properties).

### HostSystem (connected hosts only)

`vCommunity|Configuration|Advanced System Settings|{key}` (filtered to the
central check-list) · `…|Packages:{name}|{Package Name, Package Version,
Acceptance Level, Maintenance Mode Required, Package Summary, Package Type,
Package Vendor}` (filtered) · `…|Install Date|UTC` (or `…|Install Date|Read
Error` on read failure — TOOLSET GAP #1 degradation) ·
`vCommunity|Licensing:{name}|{Name, License Key, License Expiration Date,
Edition Key}` (properties) + `…|Remaining Days` (metric) ·
`vCommunity|Network|Device:{device}|{Device Name, Driver Version, Firmware
Version, Status}` (properties).

### VirtualMachine

`vCommunity|Snapshot|Count` (metric) · `vCommunity|Options|{configPath}`
(filtered to the central check-list) · `vCommunity|Configuration|Advanced
Parameters|{key}` (filtered) · `vCommunity|Configuration|SCSI Controllers|Count`
(metric) + `…|SCSI Controllers:{bus}|Type` (property) ·
`vCommunity|Guest OS|Services:{displayName}|{Service Name, Service Status,
Service Start Type}` (guest-ops) · `vCommunity|Guest OS|Operating System|{OS
Name, OS Version, OS BuildNumber, OS Architecture, OS Last Boot Up Time, OS
Release ID}` (guest-ops) · `vCommunity|Guest OS|Last Event|{n}|{Level,
Criticality, Message}` (Windows events degraded to properties — TOOLSET GAP #1) ·
`vCommunity|Guest OS|Collection Status` (DEGRADED notice when a guest-ops
collector returned no data).

---

## TOOLSET GAP #1 — foreign-resource event push

The original emits Windows event-log entries and host install-date read failures
as foreign-resource **events**. The factory `SuiteApiStitcher` facade exposes
only `pushProperties` / `pushStats` — there is no foreign-resource event/alert
push endpoint wired in the framework. Per the design's accepted staged plan,
these are degraded this release to **alertable properties** (visible, symptom-
/alert-able, never silently dropped); the `vCommunityWorld` `events_as_properties`
metric counts them. Real foreign-resource events are a **v1.1** deliverable once
a clean Suite API event-push path is proven (route to `tooling` to add
`SuiteApiStitcher.pushEvents`).
