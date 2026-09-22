package com.jtyll.pricerapi.pricing;

import com.jtyll.pricerapi.config.PricingEngineProperties;
import com.jtyll.pricerapi.pricing.dto.PricingRequest;
import com.jtyll.pricerapi.pricing.dto.PricingResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for PricingService against a fake "engine" shell script,
 * so they run without needing g++ or the real cpp-engine binary.
 * See PricingEngineIntegrationTest for a test against the real binary.
 */
class PricingServiceTest {

    @TempDir
    Path tempDir;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void pricesSuccessfullyWhenEngineReturnsValidJson() throws IOException {
        Path fakeEngine = writeFakeEngine("""
                #!/bin/sh
                echo '{"method":"bs","type":"call","price":10.450584,"durationMs":1}'
                """);

        PricingService service = new PricingService(properties(fakeEngine), objectMapper);
        PricingResponse response = service.price(sampleRequest(PricingMethod.BLACK_SCHOLES));

        assertThat(response.price()).isEqualTo(10.450584);
        assertThat(response.method()).isEqualTo(PricingMethod.BLACK_SCHOLES);
        assertThat(response.optionType()).isEqualTo(OptionType.CALL);
    }

    @Test
    void throwsPricingEngineExceptionWhenEngineReportsError() throws IOException {
        Path fakeEngine = writeFakeEngine("""
                #!/bin/sh
                echo '{"error":"s0, k, vol and t must be strictly positive"}'
                exit 1
                """);

        PricingService service = new PricingService(properties(fakeEngine), objectMapper);

        assertThatThrownBy(() -> service.price(sampleRequest(PricingMethod.BLACK_SCHOLES)))
                .isInstanceOf(PricingEngineException.class)
                .hasMessageContaining("strictly positive");
    }

    @Test
    void throwsPricingEngineExceptionWhenBinaryIsMissing() {
        PricingEngineProperties props = new PricingEngineProperties(
                tempDir.resolve("does-not-exist").toString(), 5);
        PricingService service = new PricingService(props, objectMapper);

        assertThatThrownBy(() -> service.price(sampleRequest(PricingMethod.BLACK_SCHOLES)))
                .isInstanceOf(PricingEngineException.class);
    }

    @Test
    void throwsPricingEngineExceptionWhenEngineTimesOut() throws IOException {
        Path fakeEngine = writeFakeEngine("""
                #!/bin/sh
                sleep 5
                echo '{"price":1.0,"durationMs":5000}'
                """);

        PricingEngineProperties props = new PricingEngineProperties(fakeEngine.toString(), 1);
        PricingService service = new PricingService(props, objectMapper);

        assertThatThrownBy(() -> service.price(sampleRequest(PricingMethod.BLACK_SCHOLES)))
                .isInstanceOf(PricingEngineException.class)
                .hasMessageContaining("timed out");
    }

    private Path writeFakeEngine(String script) throws IOException {
        Path path = tempDir.resolve("fake_engine.sh");
        Files.writeString(path, script);
        Files.setPosixFilePermissions(path, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        return path;
    }

    private static PricingEngineProperties properties(Path binary) {
        return new PricingEngineProperties(binary.toString(), 5);
    }

    private static PricingRequest sampleRequest(PricingMethod method) {
        return new PricingRequest(method, OptionType.CALL, 100.0, 100.0, 0.05, 0.2, 1.0, null, null);
    }
}
