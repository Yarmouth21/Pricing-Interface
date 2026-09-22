package com.jtyll.pricerapi.pricing;

/**
 * Thrown when the pricing engine is already running at its configured
 * concurrency limit (see PricingEngineProperties.maxConcurrentRequests)
 * and a new request can't be admitted. Distinct from PricingEngineException
 * so the controller can map it to 503 rather than 502.
 */
public class PricingEngineBusyException extends PricingEngineException {

    public PricingEngineBusyException(String message) {
        super(message);
    }
}
