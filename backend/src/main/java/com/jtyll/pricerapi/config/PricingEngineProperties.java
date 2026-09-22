package com.jtyll.pricerapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pricing.engine")
public record PricingEngineProperties(
        String binaryPath,
        long timeoutSeconds,
        int maxConcurrentRequests
) {
    private static final int DEFAULT_MAX_CONCURRENT_REQUESTS = 4;

    public PricingEngineProperties {
        if (timeoutSeconds <= 0) {
            timeoutSeconds = 30;
        }
        if (maxConcurrentRequests <= 0) {
            maxConcurrentRequests = DEFAULT_MAX_CONCURRENT_REQUESTS;
        }
    }
}
