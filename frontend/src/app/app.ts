import { Component } from '@angular/core';
import { PricingForm } from './pricing/pricing-form/pricing-form';

@Component({
  imports: [PricingForm],
  selector: 'app-root',
  styleUrl: './app.scss',
  templateUrl: './app.html',
})
export class App {}
