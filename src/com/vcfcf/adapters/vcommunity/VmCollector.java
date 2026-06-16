package com.vcfcf.adapters.vcommunity;

import com.integrien.alive.common.adapter3.Logger;
import com.vcfcf.adapters.vcommunity.VCommunityConfig.WindowsMonitoring;
import com.vcfcf.adapters.vcommunity.VCommunityVSphereClient.MoInfo;
import com.vcfcf.adapters.vcommunity.VCommunityVSphereClient.MoRef;
import com.vcfcf.adapters.vcommunity.VCommunityVSphereClient.ScsiController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * VirtualMachine collector. Reads snapshot count, VM options, advanced
 * parameters (extraConfig filtered to the central check-list), SCSI controllers,
 * and — when Windows Monitoring is enabled and a Windows Guest Credential is
 * present — Windows services / OS info / event logs via {@link GuestOpsClient}.
 *
 * <p><b>Guest-ops crash-the-cycle isolation (binding — design Failure isolation
 * §, highest-risk regression).</b> Guest-ops is wrapped at TWO layers: every
 * {@link GuestOpsClient} call already catches and returns empty, and here each
 * VM's guest-ops block is additionally wrapped per-VM. A single unreachable or
 * mis-credentialed guest logs a degradation notice and the loop continues — it
 * never aborts the cycle or the property push for other VMs.
 *
 * <p><b>Windows events (TOOLSET GAP #1 degradation).</b> The original pushes each
 * matching Windows event as a foreign-resource EVENT. The factory Suite API
 * facade has no foreign-resource event push, so per the design's accepted staged
 * plan these events are degraded to a visible, alertable PROPERTY
 * representation ({@code vCommunity|Guest OS|Last Event|...}) rather than
 * silently dropped. The level→criticality mapping is preserved in the property
 * value. Real foreign-resource events are a v1.1 deliverable.
 */
final class VmCollector {

    static final class GuestScripts {
        final byte[] services;
        final byte[] osInfo;
        final byte[] events;
        GuestScripts(byte[] services, byte[] osInfo, byte[] events) {
            this.services = services;
            this.osInfo = osInfo;
            this.events = events;
        }
    }

    /** Guest-ops degradation notices collected this cycle (for the world anchor). */
    static final class Result {
        int stitched;
        int guestVmsAttempted;
        int guestVmsDegraded;
        int eventsAsProperties;
    }

    static Result collect(VCommunityVSphereClient vs, VCommunityStitcher stitcher,
            Logger log, VCommunityConfig cfg, List<String> vmAdvParameters,
            boolean advUsable, List<String> vmOptions, boolean optionsUsable,
            List<String> winServices, boolean svcUsable, String winEventXml,
            GuestOpsClient guestOps, GuestScripts scripts, long ts)
            throws Exception {
        Result result = new Result();
        List<MoInfo> vms = vs.getVms();

        boolean guestEnabled = cfg.windowsMonitoring != WindowsMonitoring.DISABLED
                && cfg.hasWindowsCredential()
                && guestOps != null && guestOps.ready();
        if (cfg.windowsMonitoring != WindowsMonitoring.DISABLED
                && !cfg.hasWindowsCredential()) {
            log.warn("VmCollector: Windows Monitoring='" + cfg.windowsMonitoring
                    + "' but no Windows Guest Credential set — guest-ops skipped "
                    + "(non-fatal). Set the Windows Guest Credential to enable.");
        }

        for (MoInfo v : vms) {
            try {
                VCommunityStitcher.Entry e = stitcher.matchVm(v.name, v.moid);
                if (e == null) continue;

                Map<String, String> props = new LinkedHashMap<>();
                Map<String, Double> stats = new LinkedHashMap<>();

                collectConfig(vs, v, props, stats, vmAdvParameters, advUsable,
                        vmOptions, optionsUsable);

                if (guestEnabled) {
                    collectGuest(vs, v, props, cfg, winServices, svcUsable,
                            winEventXml, guestOps, scripts, log, result);
                }

                stitcher.pushProperties(e.resourceId, props, ts);
                stitcher.pushStats(e.resourceId, stats, ts);
                result.stitched++;
            } catch (Exception ex) {
                log.warn("VmCollector: '" + v.name + "' failed (isolated): "
                        + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
        }
        log.info("VmCollector: stitched " + result.stitched + "/" + vms.size()
                + " VM(s); guest-ops attempted=" + result.guestVmsAttempted
                + " degraded=" + result.guestVmsDegraded);
        return result;
    }

    // ---- pure vim25 config reads ----

    private static void collectConfig(VCommunityVSphereClient vs, MoInfo v,
            Map<String, String> props, Map<String, Double> stats,
            List<String> vmAdvParameters, boolean advUsable,
            List<String> vmOptions, boolean optionsUsable) throws Exception {
        MoRef vm = v.moRef;

        // Snapshot count (0 when none — a real reading).
        Integer snapCount = vs.vmSnapshotCount(vm);
        if (snapCount != null) {
            stats.put("vCommunity|Snapshot|Count", (double) snapCount);
        }

        // VM Options — per-key dotted config-path walk over the check-list.
        if (optionsUsable && !vmOptions.isEmpty()) {
            for (String configPath : vmOptions) {
                String value = vs.vmConfigPath(vm, configPath);
                if (value != null) {
                    props.put("vCommunity|Options|" + configPath, value);
                }
            }
        }

        // Advanced Parameters — extraConfig filtered to the check-list.
        if (advUsable && !vmAdvParameters.isEmpty()) {
            Map<String, String> extra = vs.vmExtraConfig(vm);
            for (String key : vmAdvParameters) {
                String value = extra.get(key);
                if (value != null) {
                    props.put("vCommunity|Configuration|Advanced Parameters|"
                            + key, value);
                }
            }
        }

        // SCSI controllers.
        List<ScsiController> ctrls = vs.vmScsiControllers(vm);
        stats.put("vCommunity|Configuration|SCSI Controllers|Count",
                (double) ctrls.size());
        for (ScsiController c : ctrls) {
            props.put("vCommunity|Configuration|SCSI Controllers:" + c.busNumber
                    + "|Type", c.friendlyType);
            // Legacy `Config` alias the prod original ALSO emits alongside the
            // canonical `Configuration` path (same underlying data, second key
            // path) — note the pipe-delimited index `|<bus>|Type`, not the
            // colon-delimited `:<bus>|Type` of the Configuration path. Emitted
            // verbatim for like-for-like parity.
            props.put("vCommunity|Config|SCSI Controllers|" + c.busNumber
                    + "|Type", c.friendlyType);
        }
        // Legacy `Config` count alias (METRIC), parity with the prod original.
        stats.put("vCommunity|Config|SCSI Controllers|Count",
                (double) ctrls.size());

        // Guest OS / Operating System — VMware-Tools guest info (vim25 guest.*),
        // NOT the Windows-only in-guest PowerShell path. Populates for every VM
        // whose tools report it (including non-Windows guests), matching the
        // prod original verbatim. Each key is pushed only when the guest
        // actually reported it; an unreported field is SKIPPED, never a sentinel
        // (the cardinal unreadable-is-not-a-value rule).
        Map<String, String> osInfo = vs.vmGuestOsInfo(vm);
        for (Map.Entry<String, String> e : osInfo.entrySet()) {
            props.put("vCommunity|Guest OS|Operating System|" + e.getKey(),
                    e.getValue());
        }
    }

    // ---- guest-ops (Windows) ----

    private static void collectGuest(VCommunityVSphereClient vs, MoInfo v,
            Map<String, String> props, VCommunityConfig cfg,
            List<String> winServices, boolean svcUsable, String winEventXml,
            GuestOpsClient guestOps, GuestScripts scripts, Logger log,
            Result result) {
        MoRef vm = v.moRef;
        // Gate (vmService.py:131): toolsOk AND windowsGuest. Skip silently.
        String toolsStatus;
        String guestFamily;
        try {
            toolsStatus = vs.vmGuestToolsStatus(vm);
            guestFamily = vs.vmGuestFamily(vm);
        } catch (Exception ex) {
            log.warn("VmCollector: '" + v.name + "' guest gate read failed "
                    + "(isolated): " + ex.getMessage());
            return;
        }
        if (!"toolsOk".equals(toolsStatus)
                || !"windowsGuest".equals(guestFamily)) {
            return;   // not a manageable Windows guest — skip silently.
        }

        result.guestVmsAttempted++;
        boolean degraded = false;

        // Services
        if (cfg.windowsMonitoring.services() && svcUsable
                && !winServices.isEmpty() && scripts.services != null) {
            try {
                List<GuestOpsClient.ServiceRow> rows = guestOps.collectServices(
                        vm, v.name, scripts.services, winServices);
                if (rows.isEmpty()) {
                    degraded = true;
                }
                for (GuestOpsClient.ServiceRow r : rows) {
                    String base = "vCommunity|Guest OS|Services:"
                            + r.displayName + "|";
                    props.put(base + "Service Name", nz(r.name));
                    props.put(base + "Service Status", nz(r.status));
                    props.put(base + "Service Start Type", nz(r.startType));
                }
            } catch (Exception ex) {
                degraded = true;
                log.warn("VmCollector: '" + v.name + "' service guest-ops failed "
                        + "(isolated): " + ex.getMessage());
            }
        }

        // OS information — in the original this runs together with the service
        // collector under the same gate (collectVMData.py:69-72), so it is
        // bound to the Services enum value, not Event Logs.
        if (cfg.windowsMonitoring.services() && scripts.osInfo != null) {
            try {
                GuestOpsClient.OsInfoRow os = guestOps.collectOsInfo(
                        vm, v.name, scripts.osInfo);
                if (os == null) {
                    degraded = true;
                } else {
                    String base = "vCommunity|Guest OS|Operating System|";
                    props.put(base + "OS Name", nz(os.name));
                    props.put(base + "OS Version", nz(os.version));
                    props.put(base + "OS BuildNumber", nz(os.buildNumber));
                    props.put(base + "OS Architecture", nz(os.architecture));
                    props.put(base + "OS Last Boot Up Time", nz(os.lastBootUpTime));
                    props.put(base + "OS Release ID", nz(os.releaseId));
                }
            } catch (Exception ex) {
                degraded = true;
                log.warn("VmCollector: '" + v.name + "' OS-info guest-ops failed "
                        + "(isolated): " + ex.getMessage());
            }
        }

        // Event logs — degraded to alertable properties (TOOLSET GAP #1).
        if (cfg.windowsMonitoring.eventLogs() && winEventXml != null
                && scripts.events != null) {
            try {
                List<GuestOpsClient.EventRow> events = guestOps.collectEvents(
                        vm, v.name, scripts.events, winEventXml);
                int n = 0;
                for (GuestOpsClient.EventRow ev : events) {
                    n++;
                    // Property-degradation: criticality + message, alertable.
                    String crit = mapCriticality(ev.level);
                    props.put("vCommunity|Guest OS|Last Event|" + n + "|Level",
                            ev.level != null ? ev.level : "Unknown");
                    props.put("vCommunity|Guest OS|Last Event|" + n
                            + "|Criticality", crit);
                    props.put("vCommunity|Guest OS|Last Event|" + n + "|Message",
                            ev.message != null ? ev.message : "");
                    result.eventsAsProperties++;
                }
                if (n > 0) {
                    log.info("VmCollector: '" + v.name + "' surfaced " + n
                            + " Windows event(s) as properties "
                            + "(foreign-resource event push is TOOLSET GAP #1)");
                }
            } catch (Exception ex) {
                degraded = true;
                log.warn("VmCollector: '" + v.name + "' event-log guest-ops "
                        + "failed (isolated): " + ex.getMessage());
            }
        }

        if (degraded) {
            result.guestVmsDegraded++;
            props.put("vCommunity|Guest OS|Collection Status",
                    "DEGRADED — one or more guest-ops collectors returned no data "
                    + "this cycle (see adapter log)");
        }
    }

    /** Original level→criticality mapping (collect_windows_event_logs.py:168). */
    private static String mapCriticality(String level) {
        if (level == null) return "INFO";
        switch (level.trim()) {
            case "Warning":  return "WARNING";
            case "Error":    return "IMMEDIATE";
            case "Critical": return "CRITICAL";
            case "Information":
            case "Verbose":
            default:         return "INFO";
        }
    }

    private static String nz(String v) { return v != null ? v : ""; }
}
