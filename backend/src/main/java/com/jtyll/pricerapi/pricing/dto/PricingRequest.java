package com.jtyll.pricerapi.pricing.dto;

import com.jtyll.pricerapi.pricing.OptionType;
import com.jtyll.pricerapi.pricing.PricingMethod;
import jakarta.validation.constraints.AssertTrue;
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
        @Min(1) @Max(2000) Integer steps,
        Boolean greeks
) {
    private static final int DEFAULT_PATHS = 100_000;
    private static final int DEFAULT_STEPS = 252;

    // Greeks multiply the Monte Carlo engine's cost ~7x (base price plus
    // 6 bumped re-simulations); without a tighter cap here, paths/steps
    // near their general Monte Carlo maximum would reliably exceed the
    // engine's subprocess timeout while still burning CPU until killed.
    private static final int MAX_PATHS_WITH_GREEKS = 200_000;
    private static final int MAX_STEPS_WITH_GREEKS = 500;

    public int pathsOrDefault() {
        return paths != null ? paths : DEFAULT_PATHS;
    }

    public int stepsOrDefault() {
        return steps != null ? steps : DEFAULT_STEPS;
    }

    public boolean greeksRequested() {
        return Boolean.TRUE.equals(greeks);
    }

    @AssertTrue(message = "when greeks is requested for MONTE_CARLO, paths must be <= 200,000 and steps <= 500")
    public boolean isMonteCarloGreeksWorkloadBounded() {
        if (method != PricingMethod.MONTE_CARLO || !greeksRequested()) {
            return true;
        }
        return pathsOrDefault() <= MAX_PATHS_WITH_GREEKS && stepsOrDefault() <= MAX_STEPS_WITH_GREEKS;
    }
}
