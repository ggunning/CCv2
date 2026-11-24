import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, InjectionToken, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { ActiveCartFacade } from '@spartacus/cart/base/root';
import { OCC_USER_ID_ANONYMOUS, OccEndpointsService, Product, RoutingService, UrlModule, UserIdService } from '@spartacus/core';
import { CurrentProductService } from '@spartacus/storefront';
import { firstValueFrom, of } from 'rxjs';
import { catchError, distinctUntilChanged, map, switchMap, take } from 'rxjs/operators';

/**
 * Products sold as a configurable bundle: product code -> the bundle component (template) the product
 * starts. OCC does not return a product's bundle templates, so the shop has to be told. The templates are
 * created by core-customize/casi-data/16_advanced_bundle.groovy.
 */
export const CASI_BUNDLES = new InjectionToken<Record<string, CasiBundle>>('CASI_BUNDLES', {
  factory: () => ({}),
});

/**
 * templateId: the bundle component the product starts. onlyInBundle: products that are sold with this
 * product only (its plans): the shop offers no add-to-cart for them and points to this product instead.
 * Commerce itself still accepts them as ordinary cart entries; this is about what the shop offers.
 */
export interface CasiBundle {
  templateId: string;
  onlyInBundle?: string[];
}

/** The product (code) a product is sold with only, if any. */
export function casiBundleOf(bundles: Record<string, CasiBundle>, code: string | undefined): string | null {
  return (code && Object.keys(bundles).find((main) => bundles[main].onlyInBundle?.includes(code))) || null;
}

interface Plan {
  code: string;
  name: string;
  summary: string;
  price: number;
  formattedPrice: string;
}

interface EntryGroup {
  entryGroupNumber: number;
  entries?: { entryNumber?: number }[];
  entryGroups?: EntryGroup[];
}

/**
 * "Choose your service plan" on the product page of a product that is sold as a bundle (the Advanced
 * Ontology with one of its service plans). Sits in front of the shop's add-to-cart, which the theme hides
 * while this is shown (casi-theme.scss): adding the product alone would put it in the cart outside its
 * bundle and without a plan.
 *
 * The plans are the product's references of type SELECT. "Add to cart" starts the bundle in the cart with
 * the product (configurablebundleocc: POST .../carts/{cart}/bundles) and puts the chosen plan into the
 * bundle's other component (POST .../carts/{cart}/entrygroups/{n}); Commerce checks that it is one of the
 * component's products and that there is exactly one. If the plan cannot be added, the bundle is taken out
 * of the cart again, so no half bundle is left behind. Shows nothing for any other product.
 */
@Component({
  selector: 'casi-bundle-picker',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, UrlModule],
  template: `
    @if (bundle(); as bundle) {
      <section class="casi-bundle" aria-labelledby="casi-bundle-title">
        <h2 id="casi-bundle-title">Choose your service plan</h2>
        <p class="casi-bundle-intro">{{ bundle.product.name }} comes with one service plan.</p>
        @if (bundle.plans.length) {
          <fieldset [disabled]="busy()">
            <legend class="cx-visually-hidden">Service plan</legend>
            @for (plan of bundle.plans; track plan.code) {
              <label class="casi-bundle-plan" [class.is-selected]="selected() === plan.code">
                <input type="radio" name="casi-bundle-plan" [value]="plan.code" [checked]="selected() === plan.code"
                       (change)="selected.set(plan.code)" />
                <span class="casi-bundle-plan-text">
                  <span class="casi-bundle-plan-name">{{ plan.name }}</span>
                  <span class="casi-bundle-plan-summary">{{ plan.summary }}</span>
                </span>
                <span class="casi-bundle-plan-price">{{ plan.formattedPrice }}</span>
              </label>
            }
          </fieldset>
          @if (total(); as total) {
            <p class="casi-bundle-total"><span>Total with plan</span> <strong>{{ total }}</strong></p>
          }
          <button type="button" class="btn btn-primary btn-block" [disabled]="!selected() || busy()" (click)="add(bundle)">
            {{ busy() ? 'Adding…' : 'Add to cart' }}
          </button>
          @if (!selected()) {
            <p class="casi-bundle-hint">Select a plan to continue.</p>
          }
        } @else {
          <p class="casi-bundle-hint" role="alert">No service plan is available at the moment, so this product cannot be ordered.</p>
        }
        @if (added(); as added) {
          <p class="casi-bundle-added" role="status">
            Added to your cart: {{ bundle.product.name }} with {{ added }}.
            <a [routerLink]="{ cxRoute: 'cart' } | cxUrl">View cart</a>
          </p>
        }
        @if (error(); as error) {
          <p class="casi-bundle-error" role="alert">{{ error }}</p>
        }
      </section>
    }
  `,
})
export class CasiBundlePickerComponent {
  private readonly http = inject(HttpClient);
  private readonly occ = inject(OccEndpointsService);
  private readonly userIdService = inject(UserIdService);
  private readonly activeCart = inject(ActiveCartFacade);
  private readonly bundles = inject(CASI_BUNDLES);
  private readonly routing = inject(RoutingService);

  readonly selected = signal<string | null>(null);
  readonly busy = signal(false);
  readonly added = signal<string | null>(null);
  readonly error = signal<string | null>(null);

  /** The current product when it is sold as a bundle, with its plans; null for every other product. */
  readonly bundle = toSignal(
    inject(CurrentProductService)
      .getProduct()
      .pipe(
        distinctUntilChanged((a, b) => a?.code === b?.code),
        switchMap((product) => {
          const code = product?.code ?? '';
          const templateId = this.bundles[code]?.templateId;
          this.selected.set(null);
          this.added.set(null);
          this.error.set(null);
          if (!product || !templateId) {
            return of(null);
          }
          return this.http
            .get<{ references?: { target?: Product }[] }>(
              this.occ.buildUrl('casiBundlePlans', { urlParams: { productCode: code } }),
            )
            .pipe(
              map((body) => (body.references ?? []).map((r) => r.target).filter((t): t is Product => !!t?.code)),
              catchError(() => of([] as Product[])),
              map((targets) => ({
                product,
                templateId,
                plans: targets.map<Plan>((t) => ({
                  code: t.code ?? '',
                  name: t.name ?? t.code ?? '',
                  summary: t.summary ?? '',
                  price: t.price?.value ?? 0,
                  formattedPrice: t.price?.formattedValue ?? '',
                })),
              })),
            );
        }),
      ),
    { initialValue: null },
  );

  /** Product plus chosen plan, in the product's currency format (e.g. "€139,000.00"). */
  readonly total = computed(() => {
    const bundle = this.bundle();
    const plan = bundle?.plans.find((p) => p.code === this.selected());
    const price = bundle?.product.price;
    if (!plan || price?.value == null || !price.formattedValue) {
      return null;
    }
    const sum = (price.value + plan.price).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    // keep the currency sign or code the shop already shows, replace the number
    return price.formattedValue.replace(/[\d.,]*\d/, sum);
  });

  async add(bundle: { product: Product; templateId: string; plans: Plan[] }): Promise<void> {
    const plan = bundle.plans.find((p) => p.code === this.selected());
    if (!plan || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.added.set(null);
    this.error.set(null);
    try {
      const userId = await firstValueFrom(this.userIdService.takeUserId().pipe(take(1)));
      const cart = await firstValueFrom(this.activeCart.requireLoadedCart().pipe(take(1)));
      const cartId = (userId === OCC_USER_ID_ANONYMOUS ? cart.guid : cart.code) ?? '';
      const urlParams = { userId, cartId };

      const started = await firstValueFrom(
        this.http.post<{ entry?: { entryNumber?: number } }>(this.occ.buildUrl('casiCartBundles', { urlParams }), {
          templateId: bundle.templateId,
          productCode: bundle.product.code,
          quantity: 1,
        }),
      );
      const groups = await firstValueFrom(
        this.http.get<{ entryGroups?: EntryGroup[] }>(this.occ.buildUrl('casiCartEntryGroups', { urlParams })),
      );
      // The bundle just started is the one whose component holds the cart line that was just created; the
      // plan goes into its other component. Not "the first empty component": a bundle that was removed
      // from the cart leaves its groups behind, empty, and the plan would land in the old Ontology component
      // ("is not in the product list of component 'Ontology'").
      const line = started.entry?.entryNumber;
      const holdsLine = (c: EntryGroup) => (c.entries ?? []).some((e) => e.entryNumber === line);
      const root = line == null ? undefined : (groups.entryGroups ?? []).find((g) => (g.entryGroups ?? []).some(holdsLine));
      const planGroup = (root?.entryGroups ?? []).find((c) => !holdsLine(c) && !c.entries?.length);
      if (!root || !planGroup) {
        throw new Error('The cart did not return the bundle that was just added.');
      }
      try {
        await firstValueFrom(
          this.http.post(
            this.occ.buildUrl('casiCartEntryGroup', { urlParams: { ...urlParams, entryGroupNumber: planGroup.entryGroupNumber } }),
            { product: { code: plan.code }, quantity: 1 },
          ),
        );
      } catch (e) {
        // no half bundle in the cart
        await firstValueFrom(
          this.http
            .delete(this.occ.buildUrl('casiCartEntryGroup', { urlParams: { ...urlParams, entryGroupNumber: root.entryGroupNumber } }))
            .pipe(catchError(() => of(null))),
        );
        throw e;
      }
      this.added.set(plan.name);
      // on to the cart, where the bundle shows with its plan
      this.routing.go({ cxRoute: 'cart' });
    } catch (e) {
      const detail = e instanceof HttpErrorResponse ? e.error?.errors?.[0]?.message : e instanceof Error ? e.message : '';
      this.error.set('This could not be added to your cart.' + (detail ? ' ' + detail : ''));
    } finally {
      this.activeCart.reloadActiveCart();
      this.busy.set(false);
    }
  }
}
