package com.jtyll.pricerapi.pricing.dto;

import com.jtyll.pricerapi.pricing.OptionType;
import com.jtyll.pricerapi.pricing.PricingMethod;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PricingRequest(
        @NotNull PricingMethod method,
        @NotNull OptionType optionType,
        @Positive double spot,
        @Positive double strike,
        @DecimalMin("-1.0") @DecimalMax("1.0") double riskFreeRate,
        @Positive double volatility,
        @Positive double maturity,
        @Min(1000) @Max(2_000_000) Integer paths,
        @Min(1) @Max(2000) Integer steps
) {
    private static final int DEFAULT_PATHS = 100_000;
    private static final int DEFAULT_STEPS = 252;

    public int pathsOrDefault() {
        return paths != null ? paths : DEFAULT_PATHS;
    }

    public int stepsOrDefault() {
        return steps != null ? steps : DEFAULT_STEPS;
    }
}
