package com.vcfcf.adapters.vcommunity;

import com.vcfcf.adapter.VcfCfAdapter;
import com.vcfcf.adapter.http.ManagedHttpClient;
import com.vcfcf.adapter.spi.ResourceSink;
import com.vcfcf.adapter.spi.VcfCfCollector;
import com.vcfcf.adapter.spi.VcfCfTester;
import com.vcfcf.adapter.stitch.SuiteApiStitcher;

import com.integrien.alive.common.adapter3.AdapterBase;
import com.integrien.alive.common.adapter3.MetricData;
import com.integrien.alive.common.adapter3.MetricKey;
import com.integrien.alive.common.adapter3.ResourceKey;
import com.integrien.alive.common.adapter3.ResourceStatus;
import com.integrien.alive.common.adapter3.TestParam;
import com.integrien.alive.common.adapter3.config.AdapterConfig;
import com.integrien.alive.common.adapter3.config.ResourceConfig;
import com.integrien.alive.common.adapter3.config.ResourceIdentifierConfig;
import com.integrien.alive.common.util.CommonConstants.ResourceStatusEnum;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * vCommunity adapter — native Java SDK rewrite of
 * {@code vmbro/VCF-Operations-vCommunity} (Onur Yuzseven, CC-licensed). Kills the
 * Python Integration SDK / Docker runtime; runs natively in the collector like
 * the compliance reference adapter.
 *
 * <p><b>Shape.</b> Pure ARIA_OPS-style stitching. Declares one synthetic INTERNAL
 * anchor ({@code vCommunityWorld}) so the ResourceCollection is non-empty, and
 * pushes every {@code vCommunity|...} property/metric onto existing foreign
 * VMWARE {@code ClusterComputeResource} / {@code HostSystem} /
 * {@code VirtualMachine} resources via the Suite API. No new object types, no
 * VMWARE topology edits ({@code setrelationships-foreign-adapter-scoped}).
 *
 * <p><b>Config.</b> The six central-store check-list files are fetched by name via
 * the SDK-injected Suite API channel every cycle ({@link SolutionConfigStore}),
 * with last-good caching — never silently collecting with empty lists.
 *
 * <p><b>Guest-ops.</b> Windows services / OS info / event logs via vim25
 * GuestOperationsManager, gated by the {@code Windows Monitoring} enum and the
 * optional Windows Guest Credential; per-VM crash-the-cycle isolation.
 *
 * <p><b>Events.</b> Foreign-resource event push is TOOLSET GAP #1 (the framework
 * Suite API facade exposes only properties/stats). Per the design's accepted
 * staged plan, Windows event-log findings and host install-date failures are
 * surfaced as alertable PROPERTIES this release; real foreign events are v1.1.
 */
public final class VCommunityAdapter extends VcfCfAdapter<VCommunityConfig> {

    private static final String ADAPTER_KIND = "vcfcf_vcommunity";
    private static final String WORLD_KIND = "vCommunityWorld";

    private volatile VCommunityVSphereClient vsphere;
    private volatile SuiteApiStitcher suiteStitcher;
    private volatile VCommunityStitcher stitcher;
    private volatile SolutionConfigStore configStore;

    // Bundled guest-ops scripts (read once from the conf dir).
    private volatile VmCollector.GuestScripts guestScripts;

    public VCommunityAdapter() {
        super(ADAPTER_KIND);
    }

    public VCommunityAdapter(String adapterDir, Integer adapterInstanceId) {
        super(ADAPTER_KIND, adapterDir, adapterInstanceId);
    }

    @Override
    public boolean isDynamicMetricsAllowed() {
        return true;
    }

    // =====================================================================
    // configureAdapter
    // =====================================================================

    @Override
    protected void configureAdapter(ResourceStatus status, ResourceConfig rc) {
        VCommunityConfig cfg = buildConfig(rc);
        this.config = cfg;

        this.vsphere = new VCommunityVSphereClient(
                cfg.soapHostPort(), cfg.username, cfg.password,
                sslSocketFactoryFor(cfg), cfg.allowInsecure,
                componentLogger(VCommunityVSphereClient.class));

        this.configStore = new SolutionConfigStore(
                componentLogger(SolutionConfigStore.class));

        // Ambient Suite API stitching — same path the compliance adapter proves.
        // Null on a remote collector without maintenance credentials; the cycle
        // logs the gap rather than aborting. EMPIRICAL VERIFY (design Config §):
        // confirm the injected client resolves from a non-localhost collector
        // during v1 dev — do not assume localhost.
        try {
            this.suiteStitcher = SuiteApiStitcher.create(
                    this, componentLogger(SuiteApiStitcher.class));
            this.stitcher = new VCommunityStitcher(
                    this.suiteStitcher,
                    componentLogger(VCommunityStitcher.class));
        } catch (RuntimeException e) {
            this.suiteStitcher = null;
            this.stitcher = null;
            logWarn("Ambient Suite API stitcher unavailable — vCommunity data "
                    + "will not be pushed onto VMWARE resources this instance, "
                    + "and central config files cannot be fetched: "
                    + e.getMessage());
        }

        this.guestScripts = loadGuestScripts();

        logInfo("VCommunityAdapter configured: vcenter=" + cfg.vcenterHost
                + " port=" + cfg.port + " windowsMonitoring=" + cfg.windowsMonitoring
                + " winCred=" + cfg.hasWindowsCredential()
                + " allowInsecure=" + cfg.allowInsecure
                + " stitcher=" + (stitcher != null));
    }

    private VCommunityConfig buildConfig(ResourceConfig rc) {
        // Single combined credential ("vsphere_user"): all four fields are read
        // from the one bound credential — getCredentialField reads
        // ResourceConfig.getResourceCredential() regardless of kind, so the
        // Windows guest creds now actually reach the guest-ops path.
        return new VCommunityConfig(
                getIdentifier(rc, "host"),
                getIdentifier(rc, "port"),
                getCredentialField(rc, "user"),
                getCredentialField(rc, "password"),         // REDACT-SECRET
                getCredentialField(rc, "winUser"),
                getCredentialField(rc, "winPass"),          // REDACT-SECRET
                getIdentifier(rc, "serviceMonitoring"),
                getIdentifier(rc, "winEventMonitoring"),
                getIdentifier(rc, "allowInsecure"),
                getIdentifier(rc, "esxi_adv_settings_config_file"),
                getIdentifier(rc, "esxi_vib_driver_config_file"),
                getIdentifier(rc, "vm_adv_settings_config_file"),
                getIdentifier(rc, "vm_configuration_config_file"),
                getIdentifier(rc, "win_service_config_file"),
                getIdentifier(rc, "win_event_config_file"));
    }

    private javax.net.ssl.SSLSocketFactory sslSocketFactoryFor(
            VCommunityConfig cfg) {
        if (cfg.allowInsecure) {
            logWarn("allowInsecure=true — vCenter SOAP TLS certificate "
                    + "validation is DISABLED for this instance (lab opt-out).");
            return insecureSslContext().getSocketFactory();
        }
        javax.net.ssl.SSLContext platform = getPlatformSslContext();
        if (platform != null) return platform.getSocketFactory();
        logWarn("Platform SSL context unavailable and allowInsecure=false — "
                + "using the JDK default trust store for the vCenter SOAP "
                + "connection.");
        return (javax.net.ssl.SSLSocketFactory)
                javax.net.ssl.SSLSocketFactory.getDefault();
    }

    /** Read the three guest-ops .ps1 scripts from {@code conf/profiles/scripts}. */
    private VmCollector.GuestScripts loadGuestScripts() {
        try {
            Path conf = getAdapterDescribeFile(ADAPTER_KIND, "describe.xml")
                    .getParent();
            Path scripts = conf.resolve("profiles").resolve("scripts");
            byte[] services = readQuietly(scripts.resolve("getWindowsServices.ps1"));
            byte[] osInfo = readQuietly(scripts.resolve("getWindowsOSInformation.ps1"));
            byte[] events = readQuietly(scripts.resolve("getWindowsEventLogs.ps1"));
            return new VmCollector.GuestScripts(services, osInfo, events);
        } catch (Exception e) {
            logWarn("Could not resolve guest-ops scripts directory: "
                    + e.getMessage() + " — guest-ops will be skipped");
            return new VmCollector.GuestScripts(null, null, null);
        }
    }

    private byte[] readQuietly(Path p) {
        try {
            return Files.exists(p) ? Files.readAllBytes(p) : null;
        } catch (Exception e) {
            logWarn("Could not read guest-ops script " + p + ": " + e.getMessage());
            return null;
        }
    }

    // =====================================================================
    // Tester — self-contained (controller calls onTest on a bare instance)
    // =====================================================================

    @Override
    protected VcfCfTester<VCommunityConfig> getTester() {
        return (cfg, http, param) -> {
            ResourceConfig rc = testResourceConfig(param);
            if (rc == null) {
                throw new Exception("Test-connection: no adapter-instance "
                        + "ResourceConfig on TestParam — cannot read vCenter "
                        + "host/credentials to test");
            }
            VCommunityConfig testCfg = buildConfig(rc);
            VCommunityVSphereClient testVs = new VCommunityVSphereClient(
                    testCfg.soapHostPort(), testCfg.username, testCfg.password,
                    sslSocketFactoryFor(testCfg), testCfg.allowInsecure,
                    componentLogger(VCommunityVSphereClient.class));
            testVs.connect();
            try {
                int clusters = testVs.getClusters().size();
                int hosts = testVs.getHosts().size();
                int vms = testVs.getVms().size();
                StringBuilder sb = new StringBuilder("Test OK: connected to "
                        + testCfg.vcenterHost + " — " + clusters + " cluster(s), "
                        + hosts + " host(s), " + vms + " VM(s) visible");

                // Per-file config feedback (design test() requirement). Best-
                // effort: a self-contained tester has no ambient stitcher, so
                // report which files are configured and that the central store
                // is read live during collection.
                sb.append("; central config files: ")
                        .append(testCfg.esxiAdvSettingsConfigFile).append(", ")
                        .append(testCfg.esxiVibDriverConfigFile).append(", ")
                        .append(testCfg.vmAdvSettingsConfigFile).append(", ")
                        .append(testCfg.vmConfigurationConfigFile).append(", ")
                        .append(testCfg.winServiceConfigFile).append(", ")
                        .append(testCfg.winEventConfigFile)
                        .append(" (fetched from SolutionConfig/ each cycle)");
                logInfo(sb.toString());
            } finally {
                testVs.disconnect();
            }
        };
    }

    private static ResourceConfig testResourceConfig(TestParam param) {
        if (param == null) return null;
        AdapterConfig adConf = param.getAdapterConfig();
        if (adConf == null) return null;
        return adConf.getAdapterInstResource();
    }

    // =====================================================================
    // Discovery — the single synthetic vCommunityWorld anchor
    // =====================================================================

    @Override
    protected boolean discoverOnCollect() {
        return true;
    }

    @Override
    protected void enumerateResources(ResourceSink sink)
            throws InterruptedException, Exception {
        sink.accept(worldResourceConfig());
    }

    private ResourceConfig worldResourceConfig() {
        ResourceKey key = new ResourceKey(
                "vCommunity World", WORLD_KIND, ADAPTER_KIND);
        key.addIdentifier(new ResourceIdentifierConfig(
                "world_id", "vcommunity_world", true));
        return new ResourceConfig(key);
    }

    // =====================================================================
    // Collector
    // =====================================================================

    @Override
    protected VcfCfCollector<VCommunityConfig> getCollector() {
        return new VcfCfCollector<VCommunityConfig>() {
            @Override
            public void collect(VCommunityConfig cfg, ManagedHttpClient http,
                    ResourceConfig rc, List<MetricData> out, AdapterBase adapter)
                    throws InterruptedException, Exception {
                if (isAbortRequested()) return;
                if (WORLD_KIND.equals(rc.getResourceKind())) {
                    collectWorld(out);
                }
            }

            @Override
            public ResourceStatusEnum mapCollectException(Exception e) {
                // A total collect failure (vCenter unreachable / DNS NXDOMAIN /
                // connection refused) must NOT look like a silent
                // DATA_RECEIVING-with-0-metrics cycle (the NXDOMAIN episode).
                // Map the connectivity faults to DOWN so the adapter-instance /
                // world status turns red with the actionable message thrown from
                // collectWorld(); everything else is ERROR. Either way the
                // framework sets a non-DATA_RECEIVING status and logs the message.
                Throwable t = e;
                while (t != null) {
                    if (t instanceof java.net.UnknownHostException
                            || t instanceof java.net.ConnectException
                            || t instanceof java.net.NoRouteToHostException
                            || t instanceof java.net.SocketTimeoutException) {
                        return ResourceStatusEnum.RESOURCE_STATUS_DOWN;
                    }
                    t = t.getCause();
                }
                return ResourceStatusEnum.RESOURCE_STATUS_ERROR;
            }
        };
    }

    /**
     * Per-cycle collection body. Runs once for {@code vCommunityWorld}. Walks
     * vSphere inventory, fetches the central check-lists, pushes the
     * {@code vCommunity|...} property/metric set onto matched foreign VMWARE
     * resources, and emits world-level operability metrics + config-degradation
     * notices onto {@code out}.
     */
    private void collectWorld(List<MetricData> out) throws Exception {
        long ts = System.currentTimeMillis();

        if (stitcher == null) {
            logWarn("collectWorld: Suite API stitcher unavailable — skipping "
                    + "this cycle (first-cycle null client or no maintenance "
                    + "credentials). The next cycle catches up.");
            prop(out, "Summary|status",
                    "Suite API stitcher unavailable this cycle");
            return;
        }

        // Connect to vCenter. A total failure here (unreachable host, DNS
        // NXDOMAIN, refused connection, login fault) is rethrown with an
        // actionable, secret-free message and propagated so the framework's
        // onCollect catch sets the world resource status via
        // mapCollectException() (DOWN for connectivity faults) — NOT a silent
        // DATA_RECEIVING-with-0-metrics cycle. The NXDOMAIN episode is the
        // motivating failure: cannot resolve/connect must turn the instance red.
        try {
            vsphere.ensureConnected();
        } catch (java.net.UnknownHostException uhe) {
            throw new java.net.UnknownHostException("vCommunity collection failed: "
                    + "cannot resolve vCenter host '" + config.vcenterHost
                    + "' (DNS NXDOMAIN). Check the vCenter Server adapter-instance "
                    + "field and collector DNS.");
        } catch (java.net.ConnectException | java.net.NoRouteToHostException
                | java.net.SocketTimeoutException ne) {
            throw new Exception("vCommunity collection failed: cannot connect to "
                    + "vCenter '" + config.vcenterHost + ":" + config.port
                    + "' (" + ne.getClass().getSimpleName() + ": "
                    + ne.getMessage() + "). Check vCenter reachability/port.", ne);
        }
        // Other connect-time errors (login fault, etc.) propagate as-is;
        // VCommunityVSphereClient already builds a secret-free,
        // faultstring-bearing message that the framework logs + statuses.

        // Scope foreign-resource resolution to THIS instance's vCenter (the
        // MOID-trap fix) — a bare MOID is not unique across vCenters. The UUID
        // comes from the live SOAP session; a null value degrades to the
        // unscoped matcher (single-vCenter safe).
        stitcher.setOwningVcUuid(vsphere.getVCenterInstanceUuid());

        // Load foreign VMWARE resource indexes.
        safe(() -> stitcher.loadClusterResources(), "loadClusterResources");
        safe(() -> stitcher.loadHostResources(), "loadHostResources");
        safe(() -> stitcher.loadVmResources(), "loadVmResources");

        // Fetch central check-lists (per cycle, last-good cache).
        SolutionConfigStore.FetchResult esxiAdv = configStore.fetchList(
                suiteStitcher, config.esxiAdvSettingsConfigFile);
        SolutionConfigStore.FetchResult esxiVib = configStore.fetchList(
                suiteStitcher, config.esxiVibDriverConfigFile);
        SolutionConfigStore.FetchResult vmAdv = configStore.fetchList(
                suiteStitcher, config.vmAdvSettingsConfigFile);
        SolutionConfigStore.FetchResult vmOpts = configStore.fetchList(
                suiteStitcher, config.vmConfigurationConfigFile);
        SolutionConfigStore.FetchResult winSvc = configStore.fetchList(
                suiteStitcher, config.winServiceConfigFile);
        String winEventXml = configStore.fetchRawXml(
                suiteStitcher, config.winEventConfigFile);

        // Build the guest-ops client riding the live vim25 session (only when
        // guest-ops is enabled and a Windows credential is present).
        GuestOpsClient guestOps = buildGuestOps();

        int clusters = ClusterCollector.collect(vsphere, stitcher,
                componentLogger(ClusterCollector.class), ts);
        int hosts = HostCollector.collect(vsphere, stitcher,
                componentLogger(HostCollector.class),
                esxiAdv.items, esxiAdv.usable,
                esxiVib.items, esxiVib.usable, ts);
        VmCollector.Result vmResult = VmCollector.collect(vsphere, stitcher,
                componentLogger(VmCollector.class), config,
                vmAdv.items, vmAdv.usable, vmOpts.items, vmOpts.usable,
                winSvc.items, winSvc.usable, winEventXml,
                guestOps, guestScripts, ts);

        // World operability metrics.
        metric(out, "Summary|clusters_stitched", clusters, ts);
        metric(out, "Summary|hosts_stitched", hosts, ts);
        metric(out, "Summary|vms_stitched", vmResult.stitched, ts);
        metric(out, "Summary|guest_vms_attempted",
                vmResult.guestVmsAttempted, ts);
        metric(out, "Summary|guest_vms_degraded", vmResult.guestVmsDegraded, ts);
        metric(out, "Summary|events_as_properties",
                vmResult.eventsAsProperties, ts);

        // Readable guest-ops decision diagnostics (build 9). Surface the decision
        // path on the anchor so a single install + recon tells us which leg blocks
        // devel guest-ops collection — the appliance adapter log is 404 via the
        // Suite API. Behavior-neutral: these observe the existing path, they do
        // not change what is collected.
        prop(out, "Summary|guestops_ready", vmResult.guestopsReady);
        prop(out, "Summary|guestops_vms",
                "considered=" + vmResult.guestVmsConsidered
                + " passed=" + vmResult.guestVmsPassed
                + " skipped=" + vmResult.guestVmsSkipped);
        prop(out, "Summary|guestops_skips", vmResult.guestSkipsSummary());
        // Build 10 (observability-only): the previously-swallowed guest-ops SOAP
        // fault, per failed VM — operation + vim25 fault class/message. This is
        // what names WHY in-guest collection returns zero rows on devel; bounded
        // like guestops_skips so the anchor never floods. No credential material.
        prop(out, "Summary|guestops_last_error", vmResult.guestLastErrorSummary());

        prop(out, "Summary|last_scan_timestamp", Instant.now().toString());
        prop(out, "Summary|config_file_status", summarizeConfig());
        prop(out, "Summary|status", "OK");

        logInfo("VCommunityAdapter collection complete: " + clusters
                + " clusters, " + hosts + " hosts, " + vmResult.stitched
                + " VMs (guest-ops attempted=" + vmResult.guestVmsAttempted
                + ", degraded=" + vmResult.guestVmsDegraded
                + ", events-as-properties=" + vmResult.eventsAsProperties + ")");
    }

    private GuestOpsClient buildGuestOps() {
        if (config.windowsMonitoring == VCommunityConfig.WindowsMonitoring.DISABLED
                || !config.hasWindowsCredential()) {
            return null;
        }
        try {
            VCommunityVSphereClient.MoRef fileMgr = vsphere.guestFileManager();
            VCommunityVSphereClient.MoRef procMgr = vsphere.guestProcessManager();
            if (fileMgr == null || procMgr == null) {
                logWarn("Guest-ops requested but guestOperationsManager "
                        + "fileManager/processManager unavailable — guest-ops "
                        + "skipped this cycle");
                return null;
            }
            return new GuestOpsClient(vsphere.vcenterUrl(),
                    vsphere.sessionCookie(), sslSocketFactoryFor(config),
                    config.allowInsecure, fileMgr, procMgr,
                    config.winUser, config.winPassword,        // REDACT-SECRET
                    componentLogger(GuestOpsClient.class));
        } catch (Exception e) {
            logWarn("Could not build guest-ops client (isolated): "
                    + e.getMessage());
            return null;
        }
    }

    private String summarizeConfig() {
        Map<String, String> status = configStore.lastStatus();
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : status.entrySet()) {
            if (!first) sb.append("; ");
            sb.append(e.getValue());
            first = false;
        }
        return sb.length() == 0 ? "no config files fetched" : sb.toString();
    }

    private interface Action { void run() throws Exception; }

    private void safe(Action a, String label) {
        try {
            a.run();
        } catch (Exception e) {
            logWarn("Stitcher " + label + " failed: " + e.getMessage());
        }
    }

    private static void metric(List<MetricData> out, String key, double value,
            long ts) {
        out.add(new MetricData(new MetricKey(key), ts, value));
    }

    private static void prop(List<MetricData> out, String key, String value) {
        out.add(new MetricData(new MetricKey(true, key),
                System.currentTimeMillis(), value != null ? value : ""));
    }

    // =====================================================================
    // onDiscard
    // =====================================================================

    @Override
    public void onDiscard() {
        if (vsphere != null) vsphere.disconnect();
        if (suiteStitcher != null) suiteStitcher.discard();
        super.onDiscard();
    }
}
