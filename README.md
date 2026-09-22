# Pricer — European Option Pricing, C++ → Spring Boot → Angular

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

- **`cpp-engine/`** — `pricer_cli.cpp`, a small parameterized CLI wrapping the
  pricing methodology from my NEOMA numerical finance project (Monte Carlo
  pricing & dynamic delta hedging under Black-Scholes). It takes option
  parameters as CLI flags and prints a single-line JSON result to stdout —
  no network, no IPC library, just a process boundary with a stable contract.
- **`backend/`** — Spring Boot 4 REST API. `PricingService` shells out to the
  compiled binary via `ProcessBuilder`, enforces a timeout, and maps its JSON
  output (or its errors) onto typed responses. Every request is logged
  (params in, price out) via SLF4J.
- **`frontend/`** — Angular 22 standalone app with a reactive form for the
  option parameters, calling the API and rendering the price, standard error
  (Monte Carlo), computation time, and optionally the Greeks.

## Why a subprocess instead of JNI/JNA?

The C++ code stays fully independent, compiles with a single `g++` command,
and is trivial to test in isolation (see the CI job below). The trade-off is
per-call process startup overhead, which is negligible here (a few
milliseconds for Black-Scholes, ~1s for a 200k-path Monte Carlo run) — this
optimizes for clarity and portability over raw throughput, which is the right
trade-off for a pricing tool used interactively rather than one under
high-frequency load.

## Run it

### Option A — Docker Compose (recommended)

Requires only Docker.

```bash
docker compose up --build
```

- Frontend: [http://localhost:4200](http://localhost:4200)
- Backend API: [http://localhost:8080/api/v1/pricing](http://localhost:8080/api/v1/pricing)

The frontend container's nginx reverse-proxies `/api/**` to the backend
container, so the Angular app talks to a relative `/api/v1/pricing` in
production — no CORS, no hardcoded hostnames.

### Option B — Run each part locally

**1. Compile the C++ engine:**

```bash
cd cpp-engine
g++ -std=c++17 -O2 -o pricer_cli pricer_cli.cpp
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
  "steps": 252,
  "greeks": true
}
```

`method` is `BLACK_SCHOLES` or `MONTE_CARLO`; `paths`/`steps` are optional and
only used for Monte Carlo (default 100,000 paths × 252 steps); `greeks` is
optional (default `false`) — see [Greeks](#greeks) below.

Response:

```json
{
  "method": "MONTE_CARLO",
  "optionType": "CALL",
  "price": 10.4523,
  "stdError": 0.0329,
  "paths": 100000,
  "steps": 252,
  "delta": 0.6368,
  "gamma": 0.0188,
  "theta": -6.4090,
  "vega": 37.3946,
  "durationMs": 3805
}
```

`delta`/`gamma`/`theta`/`vega` are only present when `greeks: true` was
requested; otherwise they're omitted. Validation errors return `400` with a
field-level breakdown; a pricing engine failure (bad binary path, timeout,
crash) returns `502`.

## Greeks

Ticking "Afficher les grecques" in the UI (or passing `"greeks": true` in the
request) additionally returns the four main first/second-order sensitivities:

| Greek | Meaning | Sign convention |
|-------|---------|------------------|
| **Delta** | ∂Price / ∂Spot — hedge ratio | positive for a call (0 to 1), negative for a put (-1 to 0) |
| **Gamma** | ∂Delta / ∂Spot — convexity, same for call and put | always ≥ 0 |
| **Theta** | ∂Price / ∂(calendar time) — time decay per year | usually negative for a long option |
| **Vega**  | ∂Price / ∂Volatility, per unit (i.e. per 100 vol points) | always ≥ 0 |

How each method computes them:

- **Black-Scholes** — exact closed-form derivatives of the pricing formula
  (`blackScholesGreeks` in `pricer_cli.cpp`), returned instantly alongside the
  price.
- **Monte Carlo** — no closed form exists for a simulated price, so each Greek
  is estimated by **central finite differences**: the engine bumps one
  parameter up and down by 1% (e.g. `S₀ ± 1%` for delta/gamma) and reprices
  with a fresh simulation for each bump. Critically, every bumped simulation
  reuses the *same seeded RNG stream* as the base price — the **common random
  numbers (CRN)** technique already used for the delta estimator in the
  original academic project (`pricing.cpp`'s `calculateDelta`). Because the
  bumped and base simulations consume identical Gaussian draws, most of the
  Monte Carlo noise cancels out when the prices are subtracted, so the
  resulting Greek is far more stable than it would be with independent random
  streams. The trade-off is cost: computing all four Greeks for Monte Carlo
  requires 6 extra simulations on top of the base price (delta & gamma share
  the spot bumps), so `--greeks` roughly multiplies the engine's running time
  by ~7×.

## Tests

- **C++**: a CI smoke test compiles `pricer_cli` and checks the Black-Scholes
  price against the known analytical value (100/100/5%/20%/1y → 10.450584).
- **Backend (JUnit 5)**:
  - `PricingServiceTest` — unit tests against a fake shell-script "engine"
    (success, engine-side error, missing binary, timeout), no C++ toolchain
    required.
  - `PricingEngineIntegrationTest` — end-to-end against the real compiled
    binary (skipped automatically if it hasn't been built), including Greeks
    assertions: Black-Scholes Greeks are checked against the known analytical
    values, Monte Carlo Greeks are checked for convergence to those same
    values.
  - `PricingControllerTest` — MockMvc tests for validation and response
    mapping.
  
  Run with `cd backend && ./mvnw test`.
- **Frontend (Vitest)**: form defaults, successful pricing flow (with and
  without Greeks), backend error surfacing, and client-side validation, all
  against a mocked `HttpClient`. Run with `cd frontend && npx ng test --watch=false`.

## CI/CD

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs on every push and
pull request:

1. **cpp-engine** — compiles `pricer_cli` and smoke-tests it, uploading the
   binary as an artifact.
2. **backend** — downloads that binary, runs the full JUnit suite (including
   the real-engine integration tests) with Java 21.
3. **frontend** — installs dependencies, builds, and runs the Vitest suite.
4. **docker-publish** (main branch only) — builds the backend and frontend
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
*Numerical Methods in Finance*, MSc Finance & Big Data, NEOMA Business School
— see the original report and delta-hedging simulator for the full academic
scope, which goes beyond what's exposed in this API.
