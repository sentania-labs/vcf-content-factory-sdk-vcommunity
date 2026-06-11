package com.vcfcf.adapters.example;

import com.vcfcf.adapter.http.ManagedHttpClient;
import com.vcfcf.adapter.json.SimpleJson;

import com.integrien.alive.common.adapter3.Logger;

import java.io.IOException;
import java.net.http.HttpResponse;

/**
 * Thin REST client for the example remote system.
 *
 * <p><b>Logging.</b> The logger is the framework instance logger supplied via
 * {@code VcfCfAdapter.componentLogger(ExampleApiClient.class)} — a
 * {@code com.integrien.alive.common.adapter3.Logger} wired to the adapter
 * instance's log file and pinned to INFO. Never use {@code java.util.logging}
 * (its records do not reach the adapter log).
 *
 * <p><b>Auth.</b> The {@link ManagedHttpClient} already carries the auth strategy
 * (HTTP Basic in this template), so this client only issues requests; it never
 * sees or stores the password. // REDACT-SECRET
 *
 * <p><b>Secrets never leak.</b> Error/log paths surface only the HTTP status and
 * a redacted path — never a request body, header, or credential value.
 */
public final class ExampleApiClient {

    private final ManagedHttpClient http;
    private final Logger log;

    public ExampleApiClient(ManagedHttpClient http, Logger log) {
        this.http = http;
        this.log = log;
    }

    /** List the example items the adapter turns into ExampleResource objects. */
    public SimpleJson listItems() throws IOException, InterruptedException {
        return get("/api/items");
    }

    // --- HTTP ---

    private SimpleJson get(String path) throws IOException, InterruptedException {
        HttpResponse<String> resp = http.get(path, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            // Status + redacted path only — never echo body/headers. // REDACT-SECRET
            throw new IOException("GET " + redact(path) + " returned HTTP "
                    + resp.statusCode());
        }
        return SimpleJson.parse(resp.body());
    }

    /**
     * Strip anything secret-shaped from a path before it reaches a log or
     * exception message. The template paths carry no secrets, but real adapters
     * sometimes pass tokens as query parameters — keep this defensive.
     * // REDACT-SECRET
     */
    private static String redact(String path) {
        if (path == null) return "";
        return path.replaceAll("(?i)(token|password|apikey|api_key|secret)=[^&]*",
                "$1=***");
    }
}
