import { isPlatformBrowser } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Injectable, PLATFORM_ID, inject } from '@angular/core';
import { NavigationEnd, Router } from '@angular/router';
import { ActiveCartFacade } from '@spartacus/cart/base/root';
import {
  GlobalMessageService,
  GlobalMessageType,
  OCC_USER_ID_ANONYMOUS,
  OccEndpointsService,
  RoutingService,
  UserIdService,
} from '@spartacus/core';
import { firstValueFrom } from 'rxjs';
import { filter, take } from 'rxjs/operators';
import { CASI_BUNDLES, CasiBundle, casiBundleOf } from './bundle-picker.component';

interface Entry {
  entryNumber?: number;
  product?: { code?: string; name?: string };
}
interface Group {
  entries?: Entry[];
  entryGroups?: Group[];
}
interface BundleCart {
  entries?: Entry[];
  entryGroups?: Group[];
}

/**
 * What is wrong with a cart as far as the bundles go; null when nothing is:
 *  - a bundle that was started but has a component without a product (the Advanced Ontology without a plan),
 *  - a product sold as a bundle, or sold only inside one, that sits in the cart as an ordinary entry
 *    (added from a list, Quick Order, a saved cart or an import).
 */
export function casiBundleProblem(cart: BundleCart, bundles: Record<string, CasiBundle>): string | null {
  const roots = (cart.entryGroups ?? []).filter((g) => g.entryGroups?.length);
  const inBundle = new Set<number>();
  let incomplete = false;
  for (const root of roots) {
    // a bundle that was removed from the cart leaves its groups behind, empty: nothing to complain about
    if (!(root.entryGroups ?? []).some((part) => part.entries?.length)) {
      continue;
    }
    for (const part of root.entryGroups ?? []) {
      (part.entries ?? []).forEach((e) => e.entryNumber != null && inBundle.add(e.entryNumber));
      incomplete = incomplete || !part.entries?.length;
    }
  }
  if (incomplete) {
    return 'Your cart has a product that needs a service plan. Remove it and add it again with a plan from its product page.';
  }
  const loose = (cart.entries ?? []).find((e) => {
    const code = e.product?.code;
    return !!code && (!!bundles[code] || !!casiBundleOf(bundles, code)) && (e.entryNumber == null || !inBundle.has(e.entryNumber));
  });
  if (!loose) {
    return null;
  }
  const name = loose.product?.name ?? loose.product?.code;
  return bundles[loose.product?.code ?? '']
    ? `${name} is sold with a service plan. Remove it from your cart and add it again with a plan from its product page.`
    : `${name} is not sold on its own. Remove it from your cart and choose it when you order the product it belongs to.`;
}

/**
 * Keeps a cart that breaks a bundle out of the checkout: on every checkout page the cart is read, and when
 * casiBundleProblem finds something the customer is sent back to the cart with the reason.
 *
 * This is the shop's own check. Commerce marks an unfinished bundle (entry group "erroneous") but places
 * the order all the same, and knows nothing about a bundle product added as an ordinary entry; an order
 * sent straight to the API is not stopped by this.
 */
@Injectable({ providedIn: 'root' })
export class CasiBundleCheckoutCheck {
  private readonly router = inject(Router);
  private readonly http = inject(HttpClient);
  private readonly occ = inject(OccEndpointsService);
  private readonly userIdService = inject(UserIdService);
  private readonly activeCart = inject(ActiveCartFacade);
  private readonly routing = inject(RoutingService);
  private readonly messages = inject(GlobalMessageService);
  private readonly bundles = inject(CASI_BUNDLES);
  private readonly browser = isPlatformBrowser(inject(PLATFORM_ID));

  start(): void {
    if (!this.browser || !Object.keys(this.bundles).length) {
      return;
    }
    this.router.events
      .pipe(filter((e): e is NavigationEnd => e instanceof NavigationEnd && /\/checkout(\/|\?|$)/.test(e.urlAfterRedirects)))
      .subscribe(() => void this.check());
  }

  private async check(): Promise<void> {
    try {
      const userId = await firstValueFrom(this.userIdService.takeUserId().pipe(take(1)));
      const cart = await firstValueFrom(this.activeCart.takeActive().pipe(take(1)));
      const cartId = (userId === OCC_USER_ID_ANONYMOUS ? cart.guid : cart.code) ?? '';
      if (!cartId) {
        return;
      }
      const body = await firstValueFrom(
        this.http.get<BundleCart>(this.occ.buildUrl('casiCartBundleCheck', { urlParams: { userId, cartId } })),
      );
      const problem = casiBundleProblem(body, this.bundles);
      if (problem) {
        this.routing.go({ cxRoute: 'cart' });
        this.messages.add(problem, GlobalMessageType.MSG_TYPE_ERROR, 15000);
      }
    } catch {
      // the cart could not be read: the checkout shows its own error, nothing to add here
    }
  }
}
