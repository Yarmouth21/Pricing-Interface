package com.jtyll.pricerapi.pricing;

public enum PricingMethod {
    BLACK_SCHOLES("bs"),
    MONTE_CARLO("mc");

    private final String cliArg;

    PricingMethod(String cliArg) {
        this.cliArg = cliArg;
    }

    public String toCliArg() {
        return cliArg;
    }
}
