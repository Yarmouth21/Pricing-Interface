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
  steps?: number;
}

export interface PricingResponse {
  method: PricingMethod;
  optionType: OptionType;
  price: number;
  stdError: number | null;
  paths: number | null;
  steps: number | null;
  durationMs: number;
}

export interface ApiErrorBody {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  details?: Record<string, string>;
}
