package com.jtyll.pricerapi.pricing;

public class PricingEngineException extends RuntimeException {

    public PricingEngineException(String message) {
        super(message);
    }

    public PricingEngineException(String message, Throwable cause) {
        super(message, cause);
    }
}
