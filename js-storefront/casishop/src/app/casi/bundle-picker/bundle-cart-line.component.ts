import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, Injectable, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActiveCartFacade, CartItemContext, OrderEntry } from '@spartacus/cart/base/root';
import { OCC_USER_ID_ANONYMOUS, OccEndpointsService, UserIdService } from '@spartacus/core';
import { Observable, combineLatest, firstValueFrom, of } from 'rxjs';
import { catchError, debounceTime, distinctUntilChanged, map, shareReplay, switchMap, take } from 'rxjs/operators';
import { CASI_BUNDLES, casiBundleOf } from './bundle-picker.component';

interface Group {
  entryGroupNumber?: number;
  entries?: { entryNumber?: number }[];
  entryGroups?: Group[];
}

/**
 * Which cart line belongs to which bundle: entry number -> entry group number of the bundle (its root).
 * The cart Spartacus loads does not say (an OCC cart entry has no group numbers), so the cart's entry
 * groups are read again whenever its lines change.
 */
@Injectable({ providedIn: 'root' })
export class CasiBundleCartGroups {
  private readonly http = inject(HttpClient);
  private readonly occ = inject(OccEndpointsService);
  private readonly userIdService = inject(UserIdService);
  private readonly activeCart = inject(ActiveCartFacade);

  readonly bundleOfLine$: Observable<Map<number, number>> = this.activeCart.getEntries().pipe(
    map((entries) => entries.map((e) => e.entryNumber + ':' + e.product?.code).join(',')),
    distinctUntilChanged(),
    debounceTime(50),
    switchMap(() => this.cartUrl('casiCartBundleCheck')),
    switchMap((url) => (url ? this.http.get<{ entryGroups?: Group[] }>(url).pipe(catchError(() => of({ entryGroups: [] }))) : of({ entryGroups: [] }))),
    map((cart) => {
      const lines = new Map<number, number>();
      for (const root of cart.entryGroups ?? []) {
        for (const part of root.entryGroups ?? []) {
          for (const e of part.entries ?? []) {
            if (e.entryNumber != null && root.entryGroupNumber != null) {
              lines.set(e.entryNumber, root.entryGroupNumber);
            }
          }
        }
      }
      return lines;
    }),
    shareReplay({ bufferSize: 1, refCount: true }),
  );

  /** Takes a whole bundle out of the cart (all its lines), then reloads the cart. */
  async remove(bundleGroup: number): Promise<void> {
    const url = await firstValueFrom(this.cartUrl('casiCartEntryGroup', { entryGroupNumber: bundleGroup }));
    try {
      await firstValueFrom(this.http.delete(url));
    } finally {
      this.activeCart.reloadActiveCart();
    }
  }

  private cartUrl(endpoint: string, more: Record<string, unknown> = {}): Observable<string> {
    return combineLatest([this.userIdService.takeUserId(), this.activeCart.takeActive()]).pipe(
      take(1),
      map(([userId, cart]) => {
        const cartId = (userId === OCC_USER_ID_ANONYMOUS ? cart.guid : cart.code) ?? '';
        return cartId ? this.occ.buildUrl(endpoint, { urlParams: { userId, cartId, ...more } }) : '';
      }),
    );
  }
}

/**
 * A cart line that belongs to a bundle (the Advanced Ontology and its service plan). A bundle is one thing
 * to buy: the plan cannot be removed on its own (that would leave the product without a plan), so the
 * line's own "Remove" is hidden by the theme while this is shown (casi-theme.scss), and the product's line
 * gets "Remove bundle", which takes both out. Commerce itself refuses another quantity for these lines.
 * Shows nothing for a line outside a bundle, and nothing where the cart cannot be changed (checkout
 * review, orders).
 */
@Component({
  selector: 'casi-bundle-cart-line',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (line(); as line) {
      <div class="casi-bundle-line casi-bundle-line-locked">
        @if (line.main) {
          <p><span class="casi-bundle-badge">Bundle</span> Sold together with its service plan.</p>
          <button type="button" class="btn btn-tertiary casi-bundle-remove" [disabled]="busy()" (click)="remove(line.group)">
            {{ busy() ? 'Removing…' : 'Remove bundle' }}<span class="cx-visually-hidden">: {{ line.name }} and its service plan</span>
          </button>
          @if (failed()) {
            <p class="casi-bundle-error" role="alert">The bundle could not be removed. Please try again.</p>
          }
        } @else {
          <p><span class="casi-bundle-badge">Bundle</span> The service plan of this bundle. To change it, remove the bundle and add it again with another plan.</p>
        }
      </div>
    }
  `,
})
export class CasiBundleCartLineComponent {
  private readonly bundles = inject(CASI_BUNDLES);
  private readonly groups = inject(CasiBundleCartGroups);

  readonly busy = signal(false);
  readonly failed = signal(false);

  /** The line when it is part of a bundle in a cart that can be changed; null otherwise. */
  readonly line = toSignal(this.bundleLine(), { initialValue: null });

  private bundleLine(): Observable<{ main: boolean; group: number; name: string } | null> {
    const context = inject(CartItemContext, { optional: true });
    if (!context) {
      return of(null);
    }
    const item$: Observable<OrderEntry> = context.item$;
    return combineLatest([item$, context.readonly$, this.groups.bundleOfLine$]).pipe(
      map(([item, readonly, bundleOfLine]) => {
        const code = item?.product?.code;
        const group = item?.entryNumber == null ? undefined : bundleOfLine.get(item.entryNumber);
        if (readonly || !code || group == null) {
          return null;
        }
        const main = !!this.bundles[code];
        return main || casiBundleOf(this.bundles, code) ? { main, group, name: item.product?.name ?? code } : null;
      }),
    );
  }

  async remove(group: number): Promise<void> {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    this.failed.set(false);
    try {
      await this.groups.remove(group);
    } catch {
      this.failed.set(true);
    } finally {
      this.busy.set(false);
    }
  }
}
