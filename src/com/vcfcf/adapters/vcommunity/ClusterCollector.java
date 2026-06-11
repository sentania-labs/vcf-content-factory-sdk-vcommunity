package com.vcfcf.adapters.vcommunity;

import com.integrien.alive.common.adapter3.Logger;
import com.vcfcf.adapters.vcommunity.VCommunityVSphereClient.MoInfo;
import com.vcfcf.adapters.vcommunity.VCommunityVSphereClient.MoRef;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ClusterComputeResource collector. Reads vSphere HA / DRS / EVC configuration
 * and pushes the {@code vCommunity|Cluster Configuration|...} property/metric
 * set onto the matched foreign VMWARE ClusterComputeResource resource.
 *
 * <p>Every key is traced to a source line in the design doc (RULE-002). The HA
 * and DRS gating semantics are preserved from the original
 * ({@code ha_properties.py:23} / {@code drs_properties.py:23}): when HA is
 * disabled (dasConfig.enabled==false OR hostMonitoring=='disabled') the HA
 * properties push {@code "null"}; when DRS is disabled the DRS properties push
 * {@code "null"} and the DRS Score metric pushes {@code 0}.
 *
 * <p><b>Reflection-tolerant.</b> A property the client could not read returns
 * null and is simply skipped — never pushed as a sentinel. (Distinct from the
 * intentional original "null" string the gating logic emits when HA/DRS is
 * deliberately disabled, which is a real, observed configuration state.)
 */
final class ClusterCollector {

    private static final String P = "vCommunity|Cluster Configuration|";

    static int collect(VCommunityVSphereClient vs, VCommunityStitcher stitcher,
            Logger log, long ts) throws Exception {
        List<MoInfo> clusters = vs.getClusters();
        int stitched = 0;
        for (MoInfo c : clusters) {
            try {
                VCommunityStitcher.Entry e = stitcher.matchCluster(c.name, c.moid);
                if (e == null) continue;

                Map<String, String> props = new LinkedHashMap<>();
                Map<String, Double> stats = new LinkedHashMap<>();
                collectOne(vs, c.moRef, props, stats);

                stitcher.pushProperties(e.resourceId, props, ts);
                stitcher.pushStats(e.resourceId, stats, ts);
                stitched++;
            } catch (Exception ex) {
                // Per-cluster isolation: one cluster's read failure must not
                // abort the others or the cycle.
                log.warn("ClusterCollector: '" + c.name + "' failed (isolated): "
                        + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
        }
        log.info("ClusterCollector: stitched " + stitched + "/" + clusters.size()
                + " cluster(s)");
        return stitched;
    }

    private static void collectOne(VCommunityVSphereClient vs, MoRef cluster,
            Map<String, String> props, Map<String, Double> stats)
            throws Exception {

        // ---- vSphere HA (gated) ----
        Boolean haEnabled = vs.clusterHaEnabled(cluster);
        String hostMonitoring = vs.clusterHostMonitoring(cluster);
        boolean haOff = Boolean.FALSE.equals(haEnabled)
                || "disabled".equals(hostMonitoring);

        putHa(props, "vSphere HA|Host Monitoring", haOff, hostMonitoring);
        putHa(props, "vSphere HA|Response \\ Host Isolation", haOff,
                vs.readScalar(cluster,
                        "configuration.dasConfig.defaultVmSettings.isolationResponse"));
        putHa(props, "vSphere HA|Response \\ Default VM Restart Priority", haOff,
                vs.readScalar(cluster,
                        "configuration.dasConfig.defaultVmSettings.restartPriority"));
        putHa(props, "vSphere HA|Response \\ Datastore APD", haOff,
                vs.readScalar(cluster,
                        "configuration.dasConfig.defaultVmSettings"
                        + ".vmComponentProtectionSettings.vmStorageProtectionForAPD"));
        putHa(props, "vSphere HA|Response \\ Datastore PDL", haOff,
                vs.readScalar(cluster,
                        "configuration.dasConfig.defaultVmSettings"
                        + ".vmComponentProtectionSettings.vmStorageProtectionForPDL"));
        putHa(props, "vSphere HA|VM Monitoring", haOff,
                vs.readScalar(cluster, "configuration.dasConfig.vmMonitoring"));
        putHa(props, "vSphere HA|Heartbeat Datastore", haOff,
                vs.readScalar(cluster,
                        "configuration.dasConfig.hBDatastoreCandidatePolicy"));

        // ---- DRS (gated) ----
        Boolean drsEnabled = vs.clusterDrsEnabled(cluster);
        boolean drsOff = Boolean.FALSE.equals(drsEnabled);

        putDrs(props, "DRS|Proactive DRS", drsOff,
                vs.readScalar(cluster, "configurationEx.proactiveDrsConfig.enabled"));
        putDrs(props, "DRS|Scale Descendants Shares", drsOff,
                vs.readScalar(cluster, "configuration.drsConfig.scaleDescendantsShares"));

        // CPU Over-Commitment: "N/A" when MaxVcpusPerCore option is absent.
        String maxVcpus = vs.clusterMaxVcpusPerCore(cluster);
        props.put(P + "DRS|CPU Over-Commitment",
                maxVcpus != null ? maxVcpus : "N/A");

        // DRS Score metric: 0 when DRS disabled; else summary.drsScore.
        if (drsOff) {
            stats.put(P + "DRS|DRS Score", 0.0);
        } else {
            Double score = vs.clusterDrsScore(cluster);
            if (score != null) stats.put(P + "DRS|DRS Score", score);
            // null => unreadable => skip (never a sentinel score).
        }

        // ---- EVC ----
        String evcMode = vs.clusterEvcModeKey(cluster);
        if (evcMode != null && !evcMode.isEmpty()) {
            props.put(P + "EVC|Enabled", "True");
            props.put(P + "EVC|Mode", evcMode);
        } else {
            props.put(P + "EVC|Enabled", "False");
            props.put(P + "EVC|Mode", "null");
        }
    }

    /** HA property: "null" when HA disabled; the read value otherwise (skip if unread). */
    private static void putHa(Map<String, String> props, String key,
            boolean haOff, String value) {
        if (haOff) {
            props.put(P + key, "null");
        } else if (value != null) {
            props.put(P + key, value);
        }
        // else: HA on but value unreadable -> skip (no sentinel).
    }

    private static void putDrs(Map<String, String> props, String key,
            boolean drsOff, String value) {
        if (drsOff) {
            props.put(P + key, "null");
        } else if (value != null) {
            props.put(P + key, value);
        }
    }
}
