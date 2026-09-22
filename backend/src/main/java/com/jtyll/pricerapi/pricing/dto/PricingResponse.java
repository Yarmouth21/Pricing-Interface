package com.jtyll.pricerapi.pricing.dto;

import com.jtyll.pricerapi.pricing.OptionType;
import com.jtyll.pricerapi.pricing.PricingMethod;

public record PricingResponse(
        PricingMethod method,
        OptionType optionType,
        double price,
        Double stdError,
        Integer paths,
        Integer steps,
        Double delta,
        Double gamma,
        Double theta,
        Double vega,
        long durationMs
) {
}
