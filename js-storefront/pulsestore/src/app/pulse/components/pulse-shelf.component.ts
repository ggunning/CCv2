import { AsyncPipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { ActiveCartFacade } from '@spartacus/cart/base/root';
import {
  CmsProductCarouselComponent,
  Product,
  ProductSearchByCodeService,
  UrlModule,
} from '@spartacus/core';
import { CmsComponentData, MediaModule } from '@spartacus/storefront';
import { Observable, combineLatest, of } from 'rxjs';
import { filter, map, switchMap } from 'rxjs/operators';

interface View {
  title: string;
  products: Product[];
}

/**
 * ProductCarouselComponent as a "shelf": a heading with arrows and a row of product cards that
 * scrolls sideways with the finger, the wheel or the arrows (scroll-snap, no carousel paging).
 * Each card has a one-tap add-to-cart; products that need a choice first (variants) link to
 * their page instead.
 */
@Component({
  selector: 'pulse-shelf',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AsyncPipe, RouterLink, UrlModule, MediaModule],
  template: `
    @if (view$ | async; as view) {
      @if (view.products.length) {
        <div class="pulse-shelf__head">
          <h2>{{ view.title }}</h2>
          <div class="pulse-shelf__arrows">
            <button type="button" (click)="scroll(-1)" aria-label="Scroll back">&#8592;</button>
            <button type="button" (click)="scroll(1)" aria-label="Scroll forward">&#8594;</button>
          </div>
        </div>
        <div class="pulse-shelf__track" #track>
          @for (product of view.products; track product.code; let i = $index) {
            <article class="pulse-card">
              <a class="pulse-card__media" [routerLink]="{ cxRoute: 'product', params: product } | cxUrl" tabindex="-1">
                <cx-media [container]="product.images?.['PRIMARY']" format="product" [alt]="product.name ?? ''"></cx-media>
                <span class="pulse-card__index" aria-hidden="true">{{ rank(i) }}</span>
              </a>
              <div class="pulse-card__body">
                <a class="pulse-card__name" [routerLink]="{ cxRoute: 'product', params: product } | cxUrl">{{ product.name }}</a>
                <div class="pulse-card__row">
                  <span class="pulse-card__price">{{ product.price?.formattedValue }}</span>
                  @if (canAdd(product)) {
                    <button
                      type="button"
                      class="pulse-card__add"
                      [class.is-added]="added() === product.code"
                      (click)="add(product)"
                      [attr.aria-label]="'Add ' + product.name + ' to cart'"
                    >
                      <span aria-hidden="true">{{ added() === product.code ? '✓' : '+' }}</span>
                    </button>
                  }
                </div>
              </div>
            </article>
          }
        </div>
      }
    }
  `,
})
export class PulseShelfComponent {
  private readonly component = inject<CmsComponentData<CmsProductCarouselComponent>>(CmsComponentData);
  private readonly products = inject(ProductSearchByCodeService);
  private readonly cart = inject(ActiveCartFacade);
  private readonly track = viewChild<ElementRef<HTMLElement>>('track');

  /** Code of the product that was just added (the button shows a tick for a moment). */
  readonly added = signal('');

  readonly view$: Observable<View> = this.component.data$.pipe(
    filter((data) => Boolean(data)),
    switchMap((data) => {
      const codes = (data.productCodes ?? '').trim().split(/\s+/).filter(Boolean);
      const products$ = codes.length
        ? combineLatest(codes.map((code) => this.products.get({ code, scope: 'carousel' })))
        : of([]);
      return products$.pipe(
        map((products) => ({
          title: data.title ?? '',
          products: products.filter((p): p is Product => Boolean(p?.code)),
        })),
      );
    }),
  );

  rank(index: number): string {
    return String(index + 1).padStart(2, '0');
  }

  canAdd(product: Product): boolean {
    // search results name the product itself as its base product; a real variant names another
    const isVariant = Boolean(product.baseProduct) && product.baseProduct !== product.code;
    return (
      product.purchasable !== false &&
      !isVariant &&
      product.stock?.stockLevelStatus !== 'outOfStock'
    );
  }

  add(product: Product): void {
    if (!product.code) {
      return;
    }
    this.cart.addEntry(product.code, 1);
    this.added.set(product.code);
    setTimeout(() => this.added.update((code) => (code === product.code ? '' : code)), 1600);
  }

  scroll(direction: 1 | -1): void {
    const el = this.track()?.nativeElement;
    el?.scrollBy({ left: direction * el.clientWidth * 0.8, behavior: 'smooth' });
  }
}
