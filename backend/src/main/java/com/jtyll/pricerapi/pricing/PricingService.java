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
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    public PricingService(PricingEngineProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public PricingResponse price(PricingRequest request) {
        List<String> command = buildCommand(request);
        log.info("Pricing request received: method={} optionType={} spot={} strike={} r={} vol={} T={}",
                request.method(), request.optionType(), request.spot(), request.strike(),
                request.riskFreeRate(), request.volatility(), request.maturity());

        String stdout = runEngine(command);
        PricingResponse response = parseResponse(request, stdout);

        log.info("Pricing result: method={} optionType={} price={} durationMs={}",
                response.method(), response.optionType(), response.price(), response.durationMs());
        return response;
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

            boolean finished = process.waitFor(properties.timeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new PricingEngineException("Pricing engine timed out after " + properties.timeoutSeconds() + "s");
            }

            String stdout = readStream(process.getInputStream());
            String stderr = readStream(process.getErrorStream());

            if (process.exitValue() != 0 && stdout.isBlank()) {
                throw new PricingEngineException("Pricing engine failed: " + stderr.trim());
            }

            return stdout;
        } catch (IOException e) {
            throw new PricingEngineException("Unable to start pricing engine at " + command.get(0), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PricingEngineException("Pricing engine call was interrupted", e);
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }

    private static String readStream(java.io.InputStream inputStream) throws IOException {
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
                node.get("durationMs").asLong()
        );
    }
}
