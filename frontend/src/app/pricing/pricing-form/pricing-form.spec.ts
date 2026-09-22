import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { environment } from '../../../environments/environment';
import { OptionType, PricingMethod, PricingResponse } from '../pricing.models';
import { PricingForm } from './pricing-form';

describe('PricingForm', () => {
  let fixture: ComponentFixture<PricingForm>;
  let component: PricingForm;
  let httpMock: HttpTestingController;

  const apiUrl = `${environment.apiBaseUrl}/api/v1/pricing`;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [PricingForm],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(PricingForm);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  afterEach(() => httpMock.verify());

  it('defaults to Black-Scholes call pricing on a 100/100 ATM option', () => {
    expect(component['form'].value.method).toBe(PricingMethod.BlackScholes);
    expect(component['form'].value.optionType).toBe(OptionType.Call);
  });

  it('posts the form values and surfaces the result', () => {
    (component as any).submit();

    const req = httpMock.expectOne(apiUrl);
    expect(req.request.method).toBe('POST');
    expect(req.request.body.method).toBe(PricingMethod.BlackScholes);

    const response: PricingResponse = {
      method: PricingMethod.BlackScholes,
      optionType: OptionType.Call,
      price: 10.450584,
      stdError: null,
      paths: null,
      steps: null,
      durationMs: 1,
    };
    req.flush(response);

    expect(component['result']()?.price).toBe(10.450584);
    expect(component['loading']()).toBe(false);
  });

  it('surfaces the backend error message on failure', () => {
    (component as any).submit();

    const req = httpMock.expectOne(apiUrl);
    req.flush(
      { timestamp: 'now', status: 502, error: 'Bad Gateway', message: 'Pricing engine failed' },
      { status: 502, statusText: 'Bad Gateway' },
    );

    expect(component['errorMessage']()).toBe('Pricing engine failed');
  });

  it('does not submit an invalid form', () => {
    component['form'].controls.spot.setValue(-1);

    (component as any).submit();

    httpMock.expectNone(apiUrl);
    expect(component['form'].controls.spot.touched).toBe(true);
  });
});
