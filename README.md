# Pricer: European Option Pricing, C++ → Spring Boot → Angular

A full-stack option pricer: a **C++ numerical engine** (Black-Scholes analytical
formula + Monte Carlo simulation under the risk-neutral GBM) invoked as a
subprocess by a **Spring Boot REST API**, consumed by an **Angular** front end.

Built as a portfolio project to demonstrate an end-to-end stack: native
numerical code, a typed backend service layer, a reactive frontend, automated
tests, and a containerized deployment pipeline.

## Architecture

```
┌─────────────┐   HTTP POST /api/v1/pricing   ┌──────────────┐   subprocess    ┌───────────────┐
│   Angular   │ ─────────────────────────────▶│  Spring Boot │ ───────────────▶│  pricer_cli   │
│  (frontend) │◀───────────────────────────── │  (backend)   │◀─────────────── │  (C++ engine) │
└─────────────┘         JSON response          └──────────────┘   JSON stdout   └───────────────┘
```

- **`cpp-engine/`**: `pricer_cli.cpp`, a small parameterized CLI wrapping the
  pricing methodology from my NEOMA numerical finance project (Monte Carlo
  pricing & dynamic delta hedging under Black-Scholes). It takes option
  parameters as CLI flags and prints a single-line JSON result to stdout:
  no network, no IPC library, just a process boundary with a stable contract.
- **`backend/`**: Spring Boot 4 REST API. `PricingService` shells out to the
  compiled binary via `ProcessBuilder`, enforces a timeout, and maps its JSON
  output (or its errors) onto typed responses. Every request is logged
  (params in, price out) via SLF4J.
- **`frontend/`**: Angular 22 standalone app with a reactive form for the
  option parameters, calling the API and rendering the price, standard error
  (Monte Carlo), computation time, and optionally the Greeks.

## Why a subprocess instead of JNI/JNA?

The C++ code stays fully independent, compiles with a single `g++` command,
and is trivial to test in isolation (see the CI job below). The trade-off is
per-call process startup overhead, which is negligible here: a few
milliseconds for Black-Scholes, and (see below) single-digit milliseconds for
Monte Carlo too, since it draws the terminal price directly instead of
stepping through a time grid. This optimizes for clarity and portability over
raw throughput, which is the right trade-off for a pricing tool used
interactively rather than one under high-frequency load.

## Why simulate S_T directly for Monte Carlo?

`pricer_cli`'s Monte Carlo engine draws the terminal stock price `S_T` in a
single step, even though the CLI still accepts a `--steps` flag. This isn't
a shortcut: it's the correct implementation for what this API actually
prices, and it used to be a real inefficiency worth calling out.

The GBM log-normal transition is **exact** over any interval, not just short
ones: there's no discretization bias to reduce by subdividing `[0, T]` into
more sub-steps, unlike an Euler scheme. And a **European** payoff depends
only on `S_T`, not on the path taken to get there. So the earlier
implementation, which multiplied `steps` correlated Gaussian increments to
build up to `S_T`, was drawing the exact same distribution as one direct
draw, `steps` times more expensively, for no precision gained: 252 steps
did ~252x more work than necessary per path. Measured effect: pricing
100,000 paths with all four Greeks (7 simulations) dropped from ~3.8s to a
few milliseconds once the step loop was removed, for an identical price and
identical standard error regardless of the `--steps` value passed in.

`steps` is kept on the CLI/API surface, unused, for path-dependent payoffs
that would genuinely need it: an Asian option's payoff depends on the
*average* of `S` over monitoring dates, a barrier option's on whether `S`
*crosses* a level at any of them, both require simulating (or at least
checking) intermediate points, unlike a vanilla European. Adding those
products later means wiring `steps` back into the simulation loop for
*those* payoffs specifically, not resurrecting it for the ones that don't
need it.

## Run it

### Option A: Docker Compose (recommended)

Requires only Docker.

```bash
docker compose up --build
```

- Frontend: [http://localhost:4200](http://localhost:4200)
- Backend API: [http://localhost:8080/api/v1/pricing](http://localhost:8080/api/v1/pricing)

The frontend container's nginx reverse-proxies `/api/**` to the backend
container, so the Angular app talks to a relative `/api/v1/pricing` in
production: no CORS, no hardcoded hostnames.

### Option B: Run each part locally

**1. Compile the C++ engine:**

```bash
cd cpp-engine
g++ -std=c++17 -O2 -pthread -o pricer_cli pricer_cli.cpp
```

**2. Start the backend** (Java 21+, uses the bundled Maven wrapper):

```bash
cd backend
./mvnw spring-boot:run
```

By default it looks for the binary at `../cpp-engine/pricer_cli` (override
with the `PRICING_ENGINE_BINARY_PATH` env var).

**3. Start the frontend** (Node 22 LTS recommended):

```bash
cd frontend
npm install
npm start
```

Open [http://localhost:4200](http://localhost:4200).

## API

`POST /api/v1/pricing`

```json
{
  "method": "MONTE_CARLO",
  "optionType": "CALL",
  "spot": 100,
  "strike": 100,
  "riskFreeRate": 0.05,
  "volatility": 0.2,
  "maturity": 1.0,
  "paths": 100000,
  "greeks": true
}
```

`method` is `BLACK_SCHOLES` or `MONTE_CARLO`; `paths` is optional and only
used for Monte Carlo (default 100,000); `greeks` is optional (default
`false`; see [Greeks](#greeks) below). A `steps` field is also accepted for
Monte Carlo but currently has no effect on the price; see [Why simulate
S_T directly for Monte Carlo?](#why-simulate-s_t-directly-for-monte-carlo)
above.

Response (a real run of the request above):

```json
{
  "method": "MONTE_CARLO",
  "optionType": "CALL",
  "price": 10.474081,
  "stdError": 0.046708,
  "paths": 100000,
  "steps": 252,
  "delta": 0.636369,
  "gamma": 0.018819,
  "theta": -6.428300,
  "vega": 37.707328,
  "durationMs": 8
}
```

`delta`/`gamma`/`theta`/`vega` are only present when `greeks: true` was
requested; otherwise they're omitted. Validation errors return `400` with a
field-level breakdown; a pricing engine failure (bad binary path, timeout,
crash) returns `502`; a request arriving while the engine is already at its
concurrency limit returns `503` (see [Concurrency &
robustness](#concurrency--robustness) below).

## Greeks

Ticking "Show the Greeks" in the UI (or passing `"greeks": true` in the
request) additionally returns the four main first/second-order sensitivities:

| Greek | Meaning | Sign convention |
|-------|---------|------------------|
| **Delta** | ∂Price / ∂Spot (hedge ratio) | positive for a call (0 to 1), negative for a put (-1 to 0) |
| **Gamma** | ∂Delta / ∂Spot (convexity, same for call and put) | always ≥ 0 |
| **Theta** | ∂Price / ∂(calendar time) (time decay per year) | usually negative for a long option |
| **Vega**  | ∂Price / ∂Volatility, per unit (i.e. per 100 vol points) | always ≥ 0 |

How each method computes them:

- **Black-Scholes**: exact closed-form derivatives of the pricing formula
  (`blackScholesGreeks` in `pricer_cli.cpp`), returned instantly alongside the
  price.
- **Monte Carlo**: no closed form exists for a simulated price, so each Greek
  is estimated by **central finite differences**: the engine bumps one
  parameter up and down by 1% (e.g. `S₀ ± 1%` for delta/gamma) and reprices
  with a fresh simulation for each bump. Critically, every bumped simulation
  reuses the *same seeded RNG stream* as the base price: the **common random
  numbers (CRN)** technique already used for the delta estimator in the
  original academic project (`pricing.cpp`'s `calculateDelta`). Because the
  bumped and base simulations consume identical Gaussian draws, most of the
  Monte Carlo noise cancels out when the prices are subtracted, so the
  resulting Greek is far more stable than it would be with independent random
  streams. The trade-off is cost: computing all four Greeks for Monte Carlo
  requires 6 extra simulations on top of the base price (delta & gamma share
  the spot bumps), so `--greeks` multiplies the engine's workload by ~7×.
  Combined with the single-step `S_T` draw above, that's still cheap: the 6
  bumped simulations run concurrently (`std::async`), and a 100,000-path,
  all-four-Greeks request now completes in single-digit milliseconds; even
  the maximum allowed 2,000,000 paths finishes in about 0.1s (measured).

## Concurrency & robustness

Two things `PricingService` does to stay well-behaved under real traffic,
beyond happy-path pricing:

- **Bounded engine concurrency.** Each request spawns a `pricer_cli`
  subprocess, which itself spawns up to 6 threads for `--greeks`. Left
  unbounded, enough concurrent requests could spawn an unbounded number of
  processes and threads on the host. `PricingService` guards subprocess
  invocation with a `Semaphore` sized by
  `pricing.engine.max-concurrent-requests` (default 4); a request that
  arrives while all permits are taken gets a `503` immediately instead of
  queuing indefinitely or degrading everything else running on the box.
- **Non-blocking stdout/stderr draining.** `ProcessBuilder`'s classic trap:
  if you `waitFor()` before reading a child process's output pipes, and
  that output exceeds the OS pipe buffer, the child blocks on `write()` and
  `waitFor()` never returns on its own (only the configured timeout saves
  you). `pricer_cli`'s output is one small JSON line today, but
  `PricingService` reads stdout and stderr on their own threads
  concurrently with `waitFor()` regardless, so this can't bite later if the
  engine ever grows a chattier response.

## Tests

- **C++**: a CI smoke test compiles `pricer_cli` and checks the Black-Scholes
  price against the known analytical value (100/100/5%/20%/1y → 10.450584).
- **Backend (JUnit 5)**:
  - `PricingServiceTest`: unit tests against a fake shell-script "engine"
    (success, engine-side error, missing binary, timeout, the concurrency
    limit rejecting a request with a busy exception, and an oversized
    stdout payload to prove the concurrent stream-draining doesn't
    deadlock), no C++ toolchain required.
  - `PricingEngineIntegrationTest`: end-to-end against the real compiled
    binary (skipped automatically if it hasn't been built), including Greeks
    assertions: Black-Scholes Greeks are checked against the known analytical
    values, Monte Carlo Greeks are checked for convergence to those same
    values.
  - `PricingControllerTest`: MockMvc tests for validation and response
    mapping.
  
  Run with `cd backend && ./mvnw test`.
- **Frontend (Vitest)**: form defaults, successful pricing flow (with and
  without Greeks), backend error surfacing, and client-side validation, all
  against a mocked `HttpClient`. Run with `cd frontend && npx ng test --watch=false`.

## CI/CD

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs on every push and
pull request:

1. **cpp-engine**: compiles `pricer_cli` and smoke-tests it, uploading the
   binary as an artifact.
2. **backend**: downloads that binary, runs the full JUnit suite (including
   the real-engine integration tests) with Java 21.
3. **frontend**: installs dependencies, builds, and runs the Vitest suite.
4. **docker-publish** (main branch only): builds the backend and frontend
   Docker images and pushes them to GitHub Container Registry
   (`ghcr.io/<owner>/pricer-backend`, `ghcr.io/<owner>/pricer-frontend`),
   tagged `latest` and by commit SHA.

## Tech stack

| Layer    | Stack |
|----------|-------|
| Pricing  | C++17, `g++` |
| Backend  | Spring Boot 4, Java 21, Spring Validation, SLF4J, JUnit 5, Mockito, AssertJ |
| Frontend | Angular 22 (standalone components, signals), RxJS, Vitest |
| Ops      | Docker, Docker Compose, GitHub Actions, GHCR |

## Credits

The pricing methodology (exact log-normal GBM discretization, Monte Carlo
convergence, CRR binomial benchmarking) comes from my individual project for
*Numerical Methods in Finance*, MSc Finance & Big Data, NEOMA Business School.
See the original report and delta-hedging simulator for the full academic
scope, which goes beyond what's exposed in this API.
