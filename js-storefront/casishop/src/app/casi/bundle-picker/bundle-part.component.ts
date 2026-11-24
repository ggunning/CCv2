import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { ProductScope, ProductService, UrlModule } from '@spartacus/core';
import { CurrentProductService } from '@spartacus/storefront';
import { of } from 'rxjs';
import { distinctUntilChanged, map, switchMap } from 'rxjs/operators';
import { CASI_BUNDLES, casiBundleOf } from './bundle-picker.component';

/**
 * Product page of a product that is sold only as part of a bundle (a service plan of the Advanced
 * Ontology): says so and leads to the product it comes with, where it is chosen. Sits in front of the
 * shop's add-to-cart, which the theme hides while a .casi-bundle section is shown (casi-theme.scss).
 * Shows nothing for any other product.
 */
@Component({
  selector: 'casi-bundle-part',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, UrlModule],
  template: `
    @if (main(); as main) {
      <section class="casi-bundle casi-bundle-part" aria-labelledby="casi-bundle-part-title">
        <h2 id="casi-bundle-part-title">Sold with {{ main.name }}</h2>
        <p class="casi-bundle-intro">This service plan is not sold on its own. Choose it when you order {{ main.name }}.</p>
        <a class="btn btn-primary btn-block" [routerLink]="{ cxRoute: 'product', params: main } | cxUrl">Go to {{ main.name }}</a>
      </section>
    }
  `,
})
export class CasiBundlePartComponent {
  private readonly bundles = inject(CASI_BUNDLES);
  private readonly products = inject(ProductService);

  /** The product the current product is sold with only (code and name); null for every other product. */
  readonly main = toSignal(
    inject(CurrentProductService)
      .getProduct()
      .pipe(
        map((product) => casiBundleOf(this.bundles, product?.code)),
        distinctUntilChanged(),
        switchMap((code) =>
          code ? this.products.get(code, ProductScope.LIST).pipe(map((main) => ({ code, name: main?.name ?? code }))) : of(null),
        ),
      ),
    { initialValue: null },
  );
}
