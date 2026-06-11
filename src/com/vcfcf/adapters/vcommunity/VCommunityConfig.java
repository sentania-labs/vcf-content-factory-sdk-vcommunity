package com.vcfcf.adapters.vcommunity;

/**
 * Typed configuration POJO for the vCommunity adapter — a native Java SDK
 * rewrite of {@code vmbro/VCF-Operations-vCommunity} (Onur Yuzseven,
 * CC-licensed).
 *
 * <p>Populated from the adapter-instance {@code ResourceConfig} in
 * {@link VCommunityAdapter#configureAdapter}. Immutable: every field is
 * {@code final} and set once.
 *
 * <p><b>Credential redaction.</b> {@link #password} and {@link #winPassword}
 * are secrets. They are read here and consumed only by the vim25 SOAP login
 * ({@link VCommunityVSphereClient}) and the guest-ops authentication
 * ({@link GuestOpsClient}). They MUST NOT appear in any log line, exception
 * message, or URL. Search this repo for {@code // REDACT-SECRET}.
 *
 * <p>The six {@code *_config_file} fields hold the NAMES (no path, no
 * {@code .xml}) of check-list files in the VCF Ops central configuration-file
 * store under {@code SolutionConfig/}. Defaults match the bundled file base
 * names — see {@link SolutionConfigStore} and the design doc Config section.
 */
public final class VCommunityConfig {

    /** Windows guest-ops monitoring mode (adapter-instance enum). */
    public enum WindowsMonitoring {
        DISABLED, SERVICES, EVENT_LOGS, SERVICES_AND_EVENT_LOGS;

        /** Parse the describe.xml enum value (verbatim display strings). */
        static WindowsMonitoring parse(String raw) {
            if (raw == null) return DISABLED;
            switch (raw.trim()) {
                case "Services":             return SERVICES;
                case "Event Logs":           return EVENT_LOGS;
                case "Services + Event Logs": return SERVICES_AND_EVENT_LOGS;
                case "Disabled":
                default:                     return DISABLED;
            }
        }

        public boolean services() {
            return this == SERVICES || this == SERVICES_AND_EVENT_LOGS;
        }

        public boolean eventLogs() {
            return this == EVENT_LOGS || this == SERVICES_AND_EVENT_LOGS;
        }
    }

    public final String vcenterHost;
    public final int port;
    public final String username;
    public final String password;          // REDACT-SECRET
    public final String winUser;
    public final String winPassword;       // REDACT-SECRET
    public final WindowsMonitoring windowsMonitoring;
    public final boolean allowInsecure;

    // Central config-store file NAMES (no path / no extension).
    public final String esxiAdvSettingsConfigFile;
    public final String esxiVibDriverConfigFile;
    public final String vmAdvSettingsConfigFile;
    public final String vmConfigurationConfigFile;
    public final String winServiceConfigFile;
    public final String winEventConfigFile;

    public VCommunityConfig(
            String vcenterHost, String port, String username, String password,
            String winUser, String winPassword, String windowsMonitoring,
            String allowInsecure,
            String esxiAdvSettingsConfigFile, String esxiVibDriverConfigFile,
            String vmAdvSettingsConfigFile, String vmConfigurationConfigFile,
            String winServiceConfigFile, String winEventConfigFile) {
        this.vcenterHost = nonBlank(vcenterHost, "localhost");
        this.port = parsePort(port);
        this.username = username != null ? username : "";
        this.password = password != null ? password : "";   // REDACT-SECRET
        this.winUser = winUser != null ? winUser.trim() : "";
        this.winPassword = winPassword != null ? winPassword : ""; // REDACT-SECRET
        this.windowsMonitoring = WindowsMonitoring.parse(windowsMonitoring);
        // Strict-by-default: only the literal "true" opts into trust-all.
        this.allowInsecure = "true".equalsIgnoreCase(allowInsecure);

        this.esxiAdvSettingsConfigFile =
                nonBlank(esxiAdvSettingsConfigFile, "esxi_advanced_system_settings");
        this.esxiVibDriverConfigFile =
                nonBlank(esxiVibDriverConfigFile, "esxi_packages");
        this.vmAdvSettingsConfigFile =
                nonBlank(vmAdvSettingsConfigFile, "vm_advanced_parameters");
        this.vmConfigurationConfigFile =
                nonBlank(vmConfigurationConfigFile, "vm_options");
        this.winServiceConfigFile =
                nonBlank(winServiceConfigFile, "windows_service_list");
        this.winEventConfigFile =
                nonBlank(winEventConfigFile, "windows_event_list");
    }

    /** Whether a Windows Guest Credential is present (guest-ops can run). */
    public boolean hasWindowsCredential() {
        return winUser != null && !winUser.isEmpty();
    }

    /** vCenter /sdk endpoint host (port carried separately for the URL). */
    public String soapHostPort() {
        return port == 443 ? vcenterHost : vcenterHost + ":" + port;
    }

    private static String nonBlank(String s, String dflt) {
        return (s != null && !s.trim().isEmpty()) ? s.trim() : dflt;
    }

    private static int parsePort(String p) {
        if (p == null || p.trim().isEmpty()) return 443;
        try {
            int v = Integer.parseInt(p.trim());
            return (v > 0 && v <= 65535) ? v : 443;
        } catch (NumberFormatException e) {
            return 443;
        }
    }
}
