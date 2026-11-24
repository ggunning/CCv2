import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Product, UrlModule } from '@spartacus/core';
import { ProductListItemContext } from '@spartacus/storefront';
import { Observable, of } from 'rxjs';
import { map } from 'rxjs/operators';
import { CASI_BUNDLES, casiBundleOf } from './bundle-picker.component';

interface ListAction {
  note: string;
  label: string;
  /** read out after the label, so several such buttons in one list can be told apart */
  labelFor: string;
  target: { code: string; name?: string };
}

/**
 * An entry of a product list or grid (category pages, search) for a product that has to do with a
 * configurable bundle:
 *  - the product sold as a bundle: says that it is one and leads to its product page, where the plan is
 *    chosen (casi-bundle-picker);
 *  - a product sold only inside a bundle (a plan): says so and leads to the product it comes with.
 * Sits in front of the entry's own actions; the theme hides the entry's add-to-cart while this is shown
 * (casi-theme.scss), because that would put the product in the cart outside its bundle.
 * Shows nothing for any other product.
 */
@Component({
  selector: 'casi-bundle-list-action',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, UrlModule],
  template: `
    @if (action(); as action) {
      <div class="casi-bundle-list">
        <p class="casi-bundle-list-note"><span class="casi-bundle-badge">Bundle</span> {{ action.note }}</p>
        <a class="btn btn-primary btn-block" [routerLink]="{ cxRoute: 'product', params: action.target } | cxUrl">
          {{ action.label }}<span class="cx-visually-hidden"> {{ action.labelFor }}</span>
        </a>
      </div>
    }
  `,
})
export class CasiBundleListActionComponent {
  private readonly bundles = inject(CASI_BUNDLES);

  /** What to show for the entry's product; null for a product that has nothing to do with a bundle. */
  readonly action = toSignal(this.listAction(), { initialValue: null });

  private listAction(): Observable<ListAction | null> {
    const product$: Observable<Product | null> = inject(ProductListItemContext, { optional: true })?.product$ ?? of(null);
    return product$.pipe(
      map((product) => {
        const code = product?.code;
        if (!code) {
          return null;
        }
        if (this.bundles[code]) {
          return {
            note: 'Sold with a service plan of your choice.',
            label: 'Choose service plan',
            labelFor: 'for ' + (product.name ?? code),
            target: { code, name: product.name },
          };
        }
        const main = casiBundleOf(this.bundles, code);
        return main
          ? {
              note: 'Not sold on its own: choose it when you order the product it belongs to.',
              label: 'View bundle',
              labelFor: 'with ' + (product.name ?? code),
              target: { code: main },
            }
          : null;
      }),
    );
  }
}
