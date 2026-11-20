import { AsyncPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ProductListComponentService } from '@spartacus/storefront';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';

interface View {
  eyebrow: string;
  title: string;
  count: number;
}

/**
 * The opener of a category or search page (CMSFlexComponent, flexType PulseListingHeroComponent):
 * the category name or the search term as a large headline with the number of results. It reads
 * the same search result as the product grid below it, so it costs no extra request.
 */
@Component({
  selector: 'pulse-listing-hero',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AsyncPipe],
  template: `
    @if (view$ | async; as view) {
      <small>{{ view.eyebrow }}</small>
      <h1>{{ view.title }}</h1>
      <p><b>{{ view.count }}</b> {{ view.count === 1 ? 'product' : 'products' }}</p>
    }
  `,
})
export class PulseListingHeroComponent {
  private readonly listing = inject(ProductListComponentService);

  readonly view$: Observable<View> = this.listing.model$.pipe(
    map((page) => {
      const search = page.freeTextSearch?.trim();
      const category = page.breadcrumbs?.[0]?.facetValueName;
      return {
        eyebrow: search ? 'Search results' : 'Shop',
        title: search ? `“${search}”` : (category ?? 'All products'),
        count: page.pagination?.totalResults ?? 0,
      };
    }),
  );
}
