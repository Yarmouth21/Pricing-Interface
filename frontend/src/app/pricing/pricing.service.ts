import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { PricingRequest, PricingResponse } from './pricing.models';

@Injectable({ providedIn: 'root' })
export class PricingService {
  private readonly baseUrl = `${environment.apiBaseUrl}/api/v1/pricing`;

  constructor(private readonly http: HttpClient) {}

  price(request: PricingRequest): Observable<PricingResponse> {
    return this.http.post<PricingResponse>(this.baseUrl, request);
  }
}
