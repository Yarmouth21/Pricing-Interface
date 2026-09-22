export enum PricingMethod {
  BlackScholes = 'BLACK_SCHOLES',
  MonteCarlo = 'MONTE_CARLO',
}

export enum OptionType {
  Call = 'CALL',
  Put = 'PUT',
}

export interface PricingRequest {
  method: PricingMethod;
  optionType: OptionType;
  spot: number;
  strike: number;
  riskFreeRate: number;
  volatility: number;
  maturity: number;
  paths?: number;
  greeks?: boolean;
}

export interface PricingResponse {
  method: PricingMethod;
  optionType: OptionType;
  price: number;
  stdError: number | null;
  paths: number | null;
  /** Reserved for future path-dependent payoffs; not shown in the UI. */
  steps: number | null;
  delta: number | null;
  gamma: number | null;
  theta: number | null;
  vega: number | null;
  durationMs: number;
}

export interface ApiErrorBody {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  details?: Record<string, string>;
}
