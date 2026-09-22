import { DecimalPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import {
  AbstractControl,
  FormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { finalize } from 'rxjs';
import { ApiErrorBody, OptionType, PricingMethod, PricingResponse } from '../pricing.models';
import { PricingService } from '../pricing.service';

// Mirrors the backend's PricingRequest.isMonteCarloGreeksWorkloadBounded:
// Greeks multiply the Monte Carlo engine's cost ~7x (base price plus 6
// bumped re-simulations), so large paths/steps combined with greeks would
// otherwise reliably exceed the engine's subprocess timeout.
const MAX_PATHS_WITH_GREEKS = 200_000;
const MAX_STEPS_WITH_GREEKS = 500;

function monteCarloGreeksWorkloadValidator(group: AbstractControl): ValidationErrors | null {
  const { method, greeks, paths, steps } = group.value;
  if (method !== PricingMethod.MonteCarlo || !greeks) {
    return null;
  }
  if (paths > MAX_PATHS_WITH_GREEKS || steps > MAX_STEPS_WITH_GREEKS) {
    return { greeksWorkloadTooLarge: true };
  }
  return null;
}

@Component({
  selector: 'app-pricing-form',
  standalone: true,
  imports: [ReactiveFormsModule, DecimalPipe],
  templateUrl: './pricing-form.html',
  styleUrl: './pricing-form.scss',
})
export class PricingForm {
  protected readonly PricingMethod = PricingMethod;
  protected readonly OptionType = OptionType;
  protected readonly maxPathsWithGreeks = MAX_PATHS_WITH_GREEKS;
  protected readonly maxStepsWithGreeks = MAX_STEPS_WITH_GREEKS;

  private readonly fb = inject(FormBuilder);
  private readonly pricingService = inject(PricingService);

  protected readonly loading = signal(false);
  protected readonly result = signal<PricingResponse | null>(null);
  protected readonly errorMessage = signal<string | null>(null);

  protected readonly form = this.fb.group(
    {
      method: [PricingMethod.BlackScholes, Validators.required],
      optionType: [OptionType.Call, Validators.required],
      spot: [100, [Validators.required, Validators.min(0.01)]],
      strike: [100, [Validators.required, Validators.min(0.01)]],
      riskFreeRate: [0.05, [Validators.required, Validators.min(-1), Validators.max(1)]],
      volatility: [0.2, [Validators.required, Validators.min(0.0001)]],
      maturity: [1, [Validators.required, Validators.min(0.0001)]],
      paths: [100000, [Validators.min(1000), Validators.max(2000000)]],
      steps: [252, [Validators.min(1), Validators.max(2000)]],
      greeks: [false],
    },
    { validators: monteCarloGreeksWorkloadValidator },
  );

  protected get isMonteCarlo(): boolean {
    return this.form.controls.method.value === PricingMethod.MonteCarlo;
  }

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const value = this.form.getRawValue();
    this.loading.set(true);
    this.errorMessage.set(null);
    this.result.set(null);

    this.pricingService
      .price({
        method: value.method!,
        optionType: value.optionType!,
        spot: value.spot!,
        strike: value.strike!,
        riskFreeRate: value.riskFreeRate!,
        volatility: value.volatility!,
        maturity: value.maturity!,
        ...(value.method === PricingMethod.MonteCarlo
          ? { paths: value.paths!, steps: value.steps! }
          : {}),
        greeks: value.greeks!,
      })
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (response) => this.result.set(response),
        error: (err: HttpErrorResponse) => this.errorMessage.set(this.extractErrorMessage(err)),
      });
  }

  private extractErrorMessage(err: HttpErrorResponse): string {
    const body = err.error as ApiErrorBody | undefined;
    if (body?.message) {
      return body.message;
    }
    if (err.status === 0) {
      return "Impossible de joindre l'API de pricing. Est-elle démarrée ?";
    }
    return `Erreur inattendue (HTTP ${err.status}).`;
  }
}
