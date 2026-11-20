import { Injectable, inject } from '@angular/core';
import { ActiveCartFacade } from '@spartacus/cart/base/root';
import { PageLayoutHandler } from '@spartacus/storefront';
import { Observable, combineLatest } from 'rxjs';
import { map } from 'rxjs/operators';

/**
 * The Pulse cart page shows either the items with the summary or the "your cart is empty" block,
 * never both (the standard handler does the same, but only for the standard CartPageTemplate).
 */
@Injectable({ providedIn: 'root' })
export class PulseCartLayoutHandler implements PageLayoutHandler {
  private readonly cart = inject(ActiveCartFacade);

  handle(slots$: Observable<string[]>, pageTemplate?: string, section?: string): Observable<string[]> {
    if (pageTemplate !== 'PulseCartTemplate' || section) {
      return slots$;
    }
    return combineLatest([slots$, this.cart.getActive(), this.cart.getLoading()]).pipe(
      map(([slots, cart, loading]) => {
        const hasItems = Boolean(cart?.totalItems);
        if (!hasItems && loading) {
          return [];
        }
        return slots.filter((slot) => (slot === 'PulseCartEmpty') !== hasItems);
      }),
    );
  }
}
