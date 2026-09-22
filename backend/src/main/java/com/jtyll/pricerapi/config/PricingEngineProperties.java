package com.jtyll.pricerapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pricing.engine")
public record PricingEngineProperties(
        String binaryPath,
        long timeoutSeconds
) {
    public PricingEngineProperties {
        if (timeoutSeconds <= 0) {
            timeoutSeconds = 30;
        }
    }
}
