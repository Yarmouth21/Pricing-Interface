package com.jtyll.pricerapi.pricing;

public enum OptionType {
    CALL, PUT;

    public String toCliArg() {
        return name().toLowerCase();
    }
}
