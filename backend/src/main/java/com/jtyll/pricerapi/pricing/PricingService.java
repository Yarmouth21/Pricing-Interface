package com.jtyll.pricerapi.pricing;

import com.jtyll.pricerapi.config.PricingEngineProperties;
import com.jtyll.pricerapi.pricing.dto.PricingRequest;
import com.jtyll.pricerapi.pricing.dto.PricingResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Invokes the native pricer_cli binary (built from ../cpp-engine/pricer_cli.cpp)
 * as a subprocess, one option-pricing request per call, and parses its
 * single-line JSON stdout back into a PricingResponse.
 */
@Service
public class PricingService {

    private static final Logger log = LoggerFactory.getLogger(PricingService.class);

    private final PricingEngineProperties properties;
    private final ObjectMapper objectMapper;

    // Each request spawns a pricer_cli subprocess, which itself spawns up
    // to 6 threads for --greeks. Without a cap, an unbounded number of
    // concurrent requests could spawn an unbounded number of subprocesses
    // and threads; this bounds how many engine invocations run at once,
    // rejecting the rest with a 503 instead of degrading the whole host.
    private final Semaphore enginePermits;

    public PricingService(PricingEngineProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.enginePermits = new Semaphore(properties.maxConcurrentRequests());
    }

    public PricingResponse price(PricingRequest request) {
        if (!enginePermits.tryAcquire()) {
            throw new PricingEngineBusyException(
                    "Pricing engine is at capacity (" + properties.maxConcurrentRequests()
                            + " concurrent requests); please try again shortly");
        }

        try {
            List<String> command = buildCommand(request);
            log.info("Pricing request received: method={} optionType={} spot={} strike={} r={} vol={} T={}",
                    request.method(), request.optionType(), request.spot(), request.strike(),
                    request.riskFreeRate(), request.volatility(), request.maturity());

            String stdout = runEngine(command);
            PricingResponse response = parseResponse(request, stdout);

            log.info("Pricing result: method={} optionType={} price={} durationMs={}",
                    response.method(), response.optionType(), response.price(), response.durationMs());
            return response;
        } finally {
            enginePermits.release();
        }
    }

    private List<String> buildCommand(PricingRequest request) {
        List<String> command = new ArrayList<>();
        command.add(resolveBinaryPath());
        command.add("--method=" + request.method().toCliArg());
        command.add("--type=" + request.optionType().toCliArg());
        command.add(arg("--s0=", request.spot()));
        command.add(arg("--k=", request.strike()));
        command.add(arg("--r=", request.riskFreeRate()));
        command.add(arg("--vol=", request.volatility()));
        command.add(arg("--t=", request.maturity()));

        if (request.method() == PricingMethod.MONTE_CARLO) {
            command.add("--paths=" + request.pathsOrDefault());
            command.add("--steps=" + request.stepsOrDefault());
        }
        if (request.greeksRequested()) {
            command.add("--greeks");
        }
        return command;
    }

    private static String arg(String prefix, double value) {
        return prefix + String.format(Locale.ROOT, "%.10f", value);
    }

    private String resolveBinaryPath() {
        return Path.of(properties.binaryPath()).toAbsolutePath().normalize().toString();
    }

    private String runEngine(List<String> command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(false)
                    .start();

            // Drain stdout/stderr concurrently with the process running,
            // not after waitFor() returns: if the child writes more than
            // the OS pipe buffer holds before anyone reads it, it blocks
            // on write() and waitFor() would never see it exit. Starting
            // both readers first avoids that classic ProcessBuilder
            // deadlock, even though today's output (a single JSON line)
            // is far smaller than the buffer.
            Process finalProcess = process;
            CompletableFuture<String> stdoutFuture =
                    CompletableFuture.supplyAsync(() -> readStreamUnchecked(finalProcess.getInputStream()));
            CompletableFuture<String> stderrFuture =
                    CompletableFuture.supplyAsync(() -> readStreamUnchecked(finalProcess.getErrorStream()));

            boolean finished = process.waitFor(properties.timeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new PricingEngineException("Pricing engine timed out after " + properties.timeoutSeconds() + "s");
            }

            String stdout = stdoutFuture.join();
            String stderr = stderrFuture.join();

            if (process.exitValue() != 0 && stdout.isBlank()) {
                throw new PricingEngineException("Pricing engine failed: " + stderr.trim());
            }

            return stdout;
        } catch (IOException e) {
            throw new PricingEngineException("Unable to start pricing engine at " + command.get(0), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PricingEngineException("Pricing engine call was interrupted", e);
        } catch (CompletionException e) {
            throw new PricingEngineException("Failed to read pricing engine output",
                    e.getCause() != null ? e.getCause() : e);
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }

    private static String readStreamUnchecked(InputStream inputStream) {
        try {
            return readStream(inputStream);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String readStream(InputStream inputStream) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString();
    }

    private PricingResponse parseResponse(PricingRequest request, String stdout) {
        if (stdout.isBlank()) {
            throw new PricingEngineException("Pricing engine produced no output");
        }

        JsonNode node;
        try {
            node = objectMapper.readTree(stdout.trim());
        } catch (tools.jackson.core.JacksonException e) {
            throw new PricingEngineException("Pricing engine returned unparsable output: " + stdout, e);
        }

        if (node.has("error")) {
            throw new PricingEngineException("Pricing engine rejected the request: " + node.get("error").asText());
        }

        return new PricingResponse(
                request.method(),
                request.optionType(),
                node.get("price").asDouble(),
                node.has("stdError") ? node.get("stdError").asDouble() : null,
                node.has("paths") ? node.get("paths").asInt() : null,
                node.has("steps") ? node.get("steps").asInt() : null,
                node.has("delta") ? node.get("delta").asDouble() : null,
                node.has("gamma") ? node.get("gamma").asDouble() : null,
                node.has("theta") ? node.get("theta").asDouble() : null,
                node.has("vega") ? node.get("vega").asDouble() : null,
                node.get("durationMs").asLong()
        );
    }
}
