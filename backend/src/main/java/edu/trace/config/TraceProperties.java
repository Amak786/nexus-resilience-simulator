package edu.trace.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Runtime settings, all overridable by environment variables (no secrets are needed).
 *
 * @param corsAllowedOrigins browser origins allowed to call the API (CORS_ALLOWED_ORIGINS, comma-separated)
 * @param maxRequestBytes    hard limit on request body size (MAX_REQUEST_BYTES)
 */
@ConfigurationProperties(prefix = "trace")
public record TraceProperties(List<String> corsAllowedOrigins, long maxRequestBytes) {}
