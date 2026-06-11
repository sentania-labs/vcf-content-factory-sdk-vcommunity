package com.vcfcf.adapters.vcommunity;

import com.integrien.alive.common.adapter3.Logger;
import com.vcfcf.adapters.vcommunity.VCommunityVSphereClient.LicenseInfo;
import com.vcfcf.adapters.vcommunity.VCommunityVSphereClient.MoInfo;
import com.vcfcf.adapters.vcommunity.VCommunityVSphereClient.MoRef;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HostSystem collector. Reads advanced settings (filtered to the central
 * check-list), VIB packages (filtered), install date, licensing, and physical
 * NIC uplinks, and pushes the {@code vCommunity|...} host property/metric set
 * onto the matched foreign VMWARE HostSystem.
 *
 * <p>Only connected hosts are processed ({@code collectHostData.py:58}) — a host
 * whose {@code runtime.connectionState} is not "connected" cannot be read
 * honestly and is skipped (no partial push).
 *
 * <p><b>Install-date read failure (TOOLSET GAP #1 degradation).</b> The original
 * emits a CRITICAL foreign-resource EVENT when {@code installDate()} throws
 * ({@code host_install_date.py:24}). The factory Suite API facade has no
 * foreign-resource event push (only properties/stats). Per the design's accepted
 * staged plan, this collector degrades the failure to a visible, alertable
 * PROPERTY ({@code vCommunity|Configuration|Install Date|Read Error}) rather than
 * silently dropping it. The degradation is documented and surfaced; real foreign
 * events are a v1.1 deliverable once the push path is proven.
 */
final class HostCollector {

    private static final String P = "vCommunity|Configuration|";

    static int collect(VCommunityVSphereClient vs, VCommunityStitcher stitcher,
            Logger log, List<String> esxiAdvSettings, boolean advUsable,
            List<String> esxiVibDrivers, boolean vibUsable, long ts)
            throws Exception {
        List<MoInfo> hosts = vs.getHosts();
        int stitched = 0;
        for (MoInfo h : hosts) {
            try {
                String connState = null;
                try {
                    connState = vs.hostConnectionState(h.moRef);
                } catch (Exception ignore) { /* unknown -> proceed */ }
                if (connState != null && !"connected".equalsIgnoreCase(connState)) {
                    log.info("HostCollector: '" + h.name + "' connectionState='"
                            + connState + "' — skipping (not connected)");
                    continue;
                }

                VCommunityStitcher.Entry e = stitcher.matchHost(h.name, h.moid);
                if (e == null) continue;

                Map<String, String> props = new LinkedHashMap<>();
                Map<String, Double> stats = new LinkedHashMap<>();
                collectOne(vs, h, props, stats, esxiAdvSettings, advUsable,
                        esxiVibDrivers, vibUsable, log);

                stitcher.pushProperties(e.resourceId, props, ts);
                stitcher.pushStats(e.resourceId, stats, ts);
                stitched++;
            } catch (Exception ex) {
                log.warn("HostCollector: '" + h.name + "' failed (isolated): "
                        + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
        }
        log.info("HostCollector: stitched " + stitched + "/" + hosts.size()
                + " host(s)");
        return stitched;
    }

    private static void collectOne(VCommunityVSphereClient vs, MoInfo h,
            Map<String, String> props, Map<String, Double> stats,
            List<String> esxiAdvSettings, boolean advUsable,
            List<String> esxiVibDrivers, boolean vibUsable, Logger log)
            throws Exception {
        MoRef host = h.moRef;

        // ---- Advanced System Settings (filtered to the central check-list) ----
        // Only collect when the check-list was usably loaded (never collect with
        // an empty list due to a fetch failure — that is handled upstream).
        if (advUsable && !esxiAdvSettings.isEmpty()) {
            Map<String, String> adv = vs.hostAdvancedSettings(host);
            for (String key : esxiAdvSettings) {
                String value = adv.get(key);
                if (value != null) {
                    props.put(P + "Advanced System Settings|" + key, value);
                }
            }
        }

        // ---- VIB packages (filtered) ----
        if (vibUsable && !esxiVibDrivers.isEmpty()) {
            MoRef imgMgr = vs.hostImageConfigManager(host);
            if (imgMgr != null) {
                List<Map<String, String>> packages = vs.fetchSoftwarePackages(imgMgr);
                for (Map<String, String> pkg : packages) {
                    String name = pkg.get("name");
                    if (name == null || !esxiVibDrivers.contains(name)) continue;
                    String base = P + "Packages:" + name + "|";
                    putIf(props, base + "Package Name", pkg.get("name"));
                    putIf(props, base + "Package Version", pkg.get("version"));
                    putIf(props, base + "Acceptance Level", pkg.get("acceptanceLevel"));
                    putIf(props, base + "Maintenance Mode Required",
                            pkg.get("maintenanceModeRequired"));
                    putIf(props, base + "Package Summary", pkg.get("summary"));
                    putIf(props, base + "Package Type", pkg.get("type"));
                    putIf(props, base + "Package Vendor", pkg.get("vendor"));
                }
            }
        }

        // ---- Install date (degradation on read failure) ----
        try {
            MoRef imgMgr = vs.hostImageConfigManager(host);
            if (imgMgr != null) {
                String installDate = vs.fetchInstallDate(imgMgr);
                props.put(P + "Install Date|UTC",
                        installDate != null ? installDate : "null");
            }
        } catch (Exception ex) {
            // TOOLSET GAP #1 fallback: surface as an alertable property, not a
            // silent drop (and not a fake event we cannot push onto the foreign
            // resource).
            props.put(P + "Install Date|Read Error",
                    "Failed to read install date: " + ex.getMessage());
            log.warn("HostCollector: install-date read failed on '" + h.name
                    + "' — surfaced as Install Date|Read Error property "
                    + "(foreign-resource event push is TOOLSET GAP #1): "
                    + ex.getMessage());
        }

        // ---- Licensing ----
        List<LicenseInfo> licenses = vs.queryAssignedLicenses(h.moid);
        for (LicenseInfo lic : licenses) {
            String name = lic.name != null ? lic.name : "Unknown";
            String base = "vCommunity|Licensing:" + name + "|";
            props.put(base + "Name", name);
            props.put(base + "License Key",
                    lic.licenseKey != null ? lic.licenseKey : "Unknown");
            props.put(base + "License Expiration Date",
                    lic.expirationDate != null ? lic.expirationDate : "null");
            props.put(base + "Edition Key",
                    lic.editionKey != null ? lic.editionKey : "Unknown");
            Long remaining = remainingDays(lic.expirationDate);
            if (remaining != null) {
                stats.put(base + "Remaining Days", (double) remaining);
            }
            // null expiration => no Remaining Days metric (never a sentinel).
        }

        // ---- Physical NIC uplinks ----
        List<Map<String, String>> pnics = vs.hostPnics(host);
        for (Map<String, String> pnic : pnics) {
            String device = pnic.get("device");
            if (device == null || device.isEmpty()) device = "N/A";
            String base = "vCommunity|Network|Device:" + device + "|";
            props.put(base + "Device Name", device);
            props.put(base + "Driver Version",
                    nz(pnic.get("driverVersion"), "N/A"));
            props.put(base + "Firmware Version",
                    nz(pnic.get("firmwareVersion"), "N/A"));
            props.put(base + "Status",
                    "true".equals(pnic.get("linkSpeedPresent"))
                            ? "Connected" : "Disconnected");
        }
    }

    /**
     * Days between now (UTC) and the license expiration date. Parses the vim
     * DateTime string ({@code 2027-01-01T00:00:00Z} / ISO offset). Returns null
     * when the date is absent or unparseable (caller skips the metric).
     */
    private static Long remainingDays(String expiration) {
        if (expiration == null || expiration.isEmpty()) return null;
        try {
            OffsetDateTime exp = OffsetDateTime.parse(expiration);
            Instant now = Instant.now();
            return ChronoUnit.DAYS.between(now.atOffset(ZoneOffset.UTC), exp);
        } catch (Exception e) {
            return null;
        }
    }

    private static void putIf(Map<String, String> props, String key, String v) {
        if (v != null) props.put(key, v);
    }

    private static String nz(String v, String dflt) {
        return (v == null || v.isEmpty()) ? dflt : v;
    }
}
