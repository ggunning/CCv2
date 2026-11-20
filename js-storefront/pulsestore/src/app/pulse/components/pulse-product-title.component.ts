import { AsyncPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Product } from '@spartacus/core';
import { CurrentProductService } from '@spartacus/storefront';
import { Observable } from 'rxjs';

/**
 * The product's name at the top of the buy box (CMSFlexComponent, flexType
 * PulseProductTitleComponent). The standard storefront shows the name in the breadcrumb bar above
 * the page; Pulse keeps it next to the price and the button, where it stays in view.
 */
@Component({
  selector: 'pulse-product-title',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AsyncPipe],
  template: `
    @if (product$ | async; as product) {
      <small>{{ product.manufacturer || 'In stock, ready to ship' }}</small>
      <h1>{{ product.name }}</h1>
    }
  `,
})
export class PulseProductTitleComponent {
  readonly product$: Observable<Product | null> = inject(CurrentProductService).getProduct();
}
