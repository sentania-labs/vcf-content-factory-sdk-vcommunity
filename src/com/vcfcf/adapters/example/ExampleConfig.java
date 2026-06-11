package com.vcfcf.adapters.example;

/**
 * Typed configuration POJO for the example adapter.
 *
 * <p>Populated from the adapter-instance {@code ResourceConfig} in
 * {@link ExampleAdapter#configureAdapter}. Immutable: every field is
 * {@code final} and set once in the constructor.
 *
 * <p><b>Credential redaction:</b> {@link #password} is a secret. It is read
 * here and consumed only by the HTTP auth strategy in
 * {@link ExampleAdapter#buildHttpClient}. It MUST NOT appear in any log line,
 * exception message, or URL. Search this repo for the marker
 * {@code // REDACT-SECRET} to audit every place a secret is handled.
 */
public final class ExampleConfig {

    /** Target host / IP of the remote system (instance identifier). */
    public final String host;

    /** HTTPS port (instance identifier; defaults to 443). */
    public final String port;

    /** API username (credential field). */
    public final String username;

    /** API password (credential field). // REDACT-SECRET — never log this. */
    public final String password;

    /** Lab opt-out: skip TLS verification for self-signed certs. */
    public final boolean allowInsecure;

    public ExampleConfig(String host, String port, String username,
            String password, String allowInsecure) {
        this.host = host;
        this.port = (port == null || port.isEmpty()) ? "443" : port;
        this.username = username;
        this.password = password; // REDACT-SECRET
        this.allowInsecure = "true".equalsIgnoreCase(allowInsecure);
    }

    /** Base URL for the remote API. Carries no secret — safe to log. */
    public String baseUrl() {
        return "https://" + host + ":" + port;
    }
}
