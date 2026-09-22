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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
                tempDir.resolve("does-not-exist").toString(), 5, 4);
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

        PricingEngineProperties props = new PricingEngineProperties(fakeEngine.toString(), 1, 4);
        PricingService service = new PricingService(props, objectMapper);

        assertThatThrownBy(() -> service.price(sampleRequest(PricingMethod.BLACK_SCHOLES)))
                .isInstanceOf(PricingEngineException.class)
                .hasMessageContaining("timed out");
    }

    @Test
    void rejectsRequestsBeyondMaxConcurrency() throws Exception {
        Path fakeEngine = writeFakeEngine("""
                #!/bin/sh
                sleep 0.5
                echo '{"method":"bs","type":"call","price":10.450584,"durationMs":500}'
                """);

        PricingEngineProperties props = new PricingEngineProperties(fakeEngine.toString(), 5, 1);
        PricingService service = new PricingService(props, objectMapper);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<PricingResponse> firstRequest =
                    pool.submit(() -> service.price(sampleRequest(PricingMethod.BLACK_SCHOLES)));
            Thread.sleep(100); // let the first request acquire the single permit and start the subprocess

            assertThatThrownBy(() -> service.price(sampleRequest(PricingMethod.BLACK_SCHOLES)))
                    .isInstanceOf(PricingEngineBusyException.class)
                    .hasMessageContaining("capacity");

            assertThat(firstRequest.get().price()).isEqualTo(10.450584);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void doesNotDeadlockWhenEngineOutputExceedsPipeBuffer() throws IOException {
        // Larger than a typical OS pipe buffer (64KB on Linux) to prove
        // stdout is drained concurrently with the process running rather
        // than only after waitFor() returns.
        Path fakeEngine = writeFakeEngine("""
                #!/bin/sh
                printf '{"method":"bs","type":"call","price":10.450584,"padding":"'
                head -c 200000 /dev/zero | tr '\\0' 'x'
                printf '","durationMs":1}'
                """);

        PricingService service = new PricingService(properties(fakeEngine), objectMapper);
        PricingResponse response = service.price(sampleRequest(PricingMethod.BLACK_SCHOLES));

        assertThat(response.price()).isEqualTo(10.450584);
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
        return new PricingEngineProperties(binary.toString(), 5, 4);
    }

    private static PricingRequest sampleRequest(PricingMethod method) {
        return new PricingRequest(method, OptionType.CALL, 100.0, 100.0, 0.05, 0.2, 1.0, null, null, null);
    }
}
