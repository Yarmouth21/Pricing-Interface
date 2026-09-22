package com.jtyll.pricerapi.pricing;

import com.jtyll.pricerapi.config.PricingEngineProperties;
import com.jtyll.pricerapi.pricing.dto.PricingRequest;
import com.jtyll.pricerapi.pricing.dto.PricingResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * End-to-end test against the real pricer_cli binary built from
 * ../cpp-engine/pricer_cli.cpp. Skipped automatically if the binary
 * hasn't been compiled yet (see cpp-engine/README.md or CI workflow).
 */
class PricingEngineIntegrationTest {

    private static final Path BINARY_PATH = Path.of("../cpp-engine/pricer_cli").toAbsolutePath().normalize();

    @Test
    @EnabledIf("engineBinaryExists")
    void blackScholesMatchesKnownAnalyticalPrice() {
        PricingService service = new PricingService(
                new PricingEngineProperties(BINARY_PATH.toString(), 30), new ObjectMapper());

        PricingResponse response = service.price(new PricingRequest(
                PricingMethod.BLACK_SCHOLES, OptionType.CALL, 100.0, 100.0, 0.05, 0.2, 1.0, null, null, null));

        assertThat(response.price()).isCloseTo(10.450584, within(1e-4));
    }

    @Test
    @EnabledIf("engineBinaryExists")
    void monteCarloConvergesCloseToBlackScholes() {
        PricingService service = new PricingService(
                new PricingEngineProperties(BINARY_PATH.toString(), 30), new ObjectMapper());

        PricingResponse response = service.price(new PricingRequest(
                PricingMethod.MONTE_CARLO, OptionType.CALL, 100.0, 100.0, 0.05, 0.2, 1.0, 200_000, 252, null));

        assertThat(response.price()).isCloseTo(10.450584, within(0.5));
        assertThat(response.stdError()).isNotNull();
    }

    @Test
    @EnabledIf("engineBinaryExists")
    void blackScholesGreeksMatchKnownAnalyticalValues() {
        PricingService service = new PricingService(
                new PricingEngineProperties(BINARY_PATH.toString(), 30), new ObjectMapper());

        PricingResponse response = service.price(new PricingRequest(
                PricingMethod.BLACK_SCHOLES, OptionType.CALL, 100.0, 100.0, 0.05, 0.2, 1.0, null, null, true));

        assertThat(response.delta()).isCloseTo(0.636831, within(1e-4));
        assertThat(response.gamma()).isCloseTo(0.018762, within(1e-4));
        assertThat(response.theta()).isCloseTo(-6.414028, within(1e-3));
        assertThat(response.vega()).isCloseTo(37.524035, within(1e-3));
    }

    @Test
    @EnabledIf("engineBinaryExists")
    void monteCarloGreeksAreCloseToBlackScholes() {
        PricingService service = new PricingService(
                new PricingEngineProperties(BINARY_PATH.toString(), 30), new ObjectMapper());

        PricingResponse response = service.price(new PricingRequest(
                PricingMethod.MONTE_CARLO, OptionType.CALL, 100.0, 100.0, 0.05, 0.2, 1.0, 100_000, 252, true));

        assertThat(response.delta()).isCloseTo(0.636831, within(0.05));
        assertThat(response.gamma()).isCloseTo(0.018762, within(0.01));
        assertThat(response.theta()).isCloseTo(-6.414028, within(1.0));
        assertThat(response.vega()).isCloseTo(37.524035, within(5.0));
    }

    static boolean engineBinaryExists() {
        return Files.isExecutable(BINARY_PATH);
    }
}
