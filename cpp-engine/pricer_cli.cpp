/*
 * pricer_cli — parameterized European option pricer (CLI wrapper)
 *
 * Exposes two pricing methods over a simple CLI/JSON contract so that
 * an external process (e.g. a Spring Boot service) can invoke this
 * binary and parse its stdout without any IPC/RPC machinery:
 *
 *   - Black-Scholes analytical closed-form
 *   - Monte Carlo under the risk-neutral GBM, exact log-normal step
 *
 * Adapted from the Monte Carlo methodology in ../pricing.cpp
 * (Numerical Methods in Finance, NEOMA Business School), generalized
 * here to arbitrary parameters, call/put payoffs and a stable CLI.
 *
 * Usage:
 *   pricer_cli --method=bs|mc --type=call|put --s0=100 --k=100 \
 *              --r=0.05 --vol=0.2 --t=1.0 [--paths=100000] [--steps=252] [--seed=42]
 *
 * Output (stdout): single-line JSON, e.g.
 *   {"method":"mc","type":"call","price":10.4523,"stdError":0.0321,"durationMs":12}
 * On error: {"error":"message"} printed to stdout with exit code 1.
 */

#include <iostream>
#include <string>
#include <unordered_map>
#include <cmath>
#include <random>
#include <chrono>
#include <stdexcept>
#include <sstream>

using namespace std;

static double normCdf(double x) {
    return 0.5 * erfc(-x / sqrt(2.0));
}

struct Params {
    string method = "bs";
    string type = "call";
    double s0 = 0, k = 0, r = 0, vol = 0, t = 0;
    long paths = 100000;
    int steps = 252;
    unsigned int seed = 42;
};

static unordered_map<string, string> parseArgs(int argc, char** argv) {
    unordered_map<string, string> out;
    for (int i = 1; i < argc; i++) {
        string arg = argv[i];
        if (arg.rfind("--", 0) != 0) continue;
        auto eq = arg.find('=');
        if (eq == string::npos) continue;
        out[arg.substr(2, eq - 2)] = arg.substr(eq + 1);
    }
    return out;
}

static Params buildParams(const unordered_map<string, string>& args) {
    Params p;
    auto get = [&](const string& key) -> string {
        auto it = args.find(key);
        if (it == args.end()) throw invalid_argument("missing required argument --" + key);
        return it->second;
    };

    if (args.count("method")) p.method = args.at("method");
    if (args.count("type")) p.type = args.at("type");
    p.s0 = stod(get("s0"));
    p.k = stod(get("k"));
    p.r = stod(get("r"));
    p.vol = stod(get("vol"));
    p.t = stod(get("t"));
    if (args.count("paths")) p.paths = stol(args.at("paths"));
    if (args.count("steps")) p.steps = stoi(args.at("steps"));
    if (args.count("seed")) p.seed = (unsigned int)stoul(args.at("seed"));

    if (p.method != "bs" && p.method != "mc")
        throw invalid_argument("method must be 'bs' or 'mc'");
    if (p.type != "call" && p.type != "put")
        throw invalid_argument("type must be 'call' or 'put'");
    if (p.s0 <= 0 || p.k <= 0 || p.vol <= 0 || p.t <= 0)
        throw invalid_argument("s0, k, vol and t must be strictly positive");
    if (p.paths <= 0 || p.steps <= 0)
        throw invalid_argument("paths and steps must be strictly positive");

    return p;
}

// Analytical Black-Scholes price for a European call or put.
static double blackScholes(const Params& p) {
    double d1 = (log(p.s0 / p.k) + (p.r + 0.5 * p.vol * p.vol) * p.t) / (p.vol * sqrt(p.t));
    double d2 = d1 - p.vol * sqrt(p.t);

    if (p.type == "call")
        return p.s0 * normCdf(d1) - p.k * exp(-p.r * p.t) * normCdf(d2);
    return p.k * exp(-p.r * p.t) * normCdf(-d2) - p.s0 * normCdf(-d1);
}

// Monte Carlo price under the risk-neutral GBM using the exact
// log-normal transition (no discretization bias), same scheme as
// calculatePrice() in ../pricing.cpp, generalized to call/put and
// arbitrary maturity/paths/steps.
struct McResult { double price; double stdError; };

static McResult monteCarlo(const Params& p) {
    mt19937 gen(p.seed);
    normal_distribution<double> dist(0.0, 1.0);
    double dt = p.t / p.steps;
    double drift = (p.r - 0.5 * p.vol * p.vol) * dt;
    double diffusion = p.vol * sqrt(dt);

    double sum = 0.0, sumSq = 0.0;
    for (long j = 0; j < p.paths; j++) {
        double st = p.s0;
        for (int i = 0; i < p.steps; i++)
            st *= exp(drift + diffusion * dist(gen));

        double payoff = (p.type == "call") ? max(st - p.k, 0.0) : max(p.k - st, 0.0);
        sum += payoff;
        sumSq += payoff * payoff;
    }

    double discount = exp(-p.r * p.t);
    double meanPayoff = sum / p.paths;
    double variance = (sumSq / p.paths) - (meanPayoff * meanPayoff);
    double stdError = discount * sqrt(max(variance, 0.0) / p.paths);

    return { discount * meanPayoff, stdError };
}

static string jsonEscape(const string& s) {
    string out;
    for (char c : s) {
        if (c == '"' || c == '\\') out += '\\';
        out += c;
    }
    return out;
}

int main(int argc, char** argv) {
    auto start = chrono::steady_clock::now();
    try {
        Params p = buildParams(parseArgs(argc, argv));
        ostringstream json;
        json.precision(6);
        json << fixed;

        if (p.method == "bs") {
            double price = blackScholes(p);
            auto durationMs = chrono::duration_cast<chrono::milliseconds>(chrono::steady_clock::now() - start).count();
            json << "{\"method\":\"bs\",\"type\":\"" << p.type << "\","
                 << "\"price\":" << price << ",\"durationMs\":" << durationMs << "}";
        } else {
            McResult res = monteCarlo(p);
            auto durationMs = chrono::duration_cast<chrono::milliseconds>(chrono::steady_clock::now() - start).count();
            json << "{\"method\":\"mc\",\"type\":\"" << p.type << "\","
                 << "\"price\":" << res.price << ",\"stdError\":" << res.stdError
                 << ",\"paths\":" << p.paths << ",\"steps\":" << p.steps
                 << ",\"durationMs\":" << durationMs << "}";
        }

        cout << json.str() << endl;
        return 0;
    } catch (const exception& e) {
        cout << "{\"error\":\"" << jsonEscape(e.what()) << "\"}" << endl;
        return 1;
    }
}
