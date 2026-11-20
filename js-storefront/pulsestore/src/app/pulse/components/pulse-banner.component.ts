import { AsyncPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, ElementRef, Renderer2, inject } from '@angular/core';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { CmsBannerComponent, Product, ProductSearchByCategoryService } from '@spartacus/core';
import { CmsComponentData, GenericLinkModule, MediaContainer, MediaModule } from '@spartacus/storefront';
import { Observable, of } from 'rxjs';
import { catchError, filter, map, startWith, switchMap, tap } from 'rxjs/operators';
import { pulseVariant, splitCallToAction, styleClasses } from '../pulse-variant';

type Kind = 'hero' | 'tile' | 'story' | 'logo' | 'plain';
interface View {
  kind: Kind;
  headline: string;
  html: SafeHtml;
  cta: string;
  url: string;
  media: MediaContainer | undefined;
  /** Products of the tile's category: their pictures are the tile's artwork. */
  products: Product[];
}

const KINDS: readonly Kind[] = ['hero', 'tile', 'story', 'logo'];
/** The scope added in PulseModule: three products of a category, with pictures. */
export const PULSE_TILE_SCOPE = 'pulseTile';

/**
 * BannerComponent / SimpleBannerComponent in the Pulse looks. All of them read the same fields:
 * headline, content (HTML; a `small` element is the eyebrow, `[[Label]]` the button), link, media.
 *
 *  - `pulse-hero`:  the home page opener: big headline, text, button, artwork;
 *  - `pulse-tile`:  a category tile. A link of the form /c/<code> pulls that category's product
 *                   pictures in as artwork, so tiles need no images of their own;
 *  - `pulse-story`: a full-width statement band;
 *  - `pulse-logo`:  the site logo;
 *  - no class:      picture with optional headline and text, linked.
 */
@Component({
  selector: 'pulse-banner',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AsyncPipe, GenericLinkModule, MediaModule],
  template: `
    @if (view$ | async; as view) {
      @switch (view.kind) {
        @case ('hero') {
          <div class="pulse-hero__copy">
            <h1>{{ view.headline }}</h1>
            <div class="pulse-rich" [innerHTML]="view.html"></div>
            @if (view.url) {
              <cx-generic-link [url]="view.url" class="pulse-btn">
                {{ view.cta || 'Shop now' }}<span aria-hidden="true">&#8594;</span>
              </cx-generic-link>
            }
          </div>
          <div class="pulse-hero__art">
            @if (view.media) {
              <cx-media [container]="view.media"></cx-media>
            }
          </div>
        }
        @case ('tile') {
          <cx-generic-link [url]="view.url || '/'" class="pulse-tile__link">
            <span class="pulse-tile__copy">
              <span class="pulse-rich" [innerHTML]="view.html"></span>
              <span class="pulse-tile__title">{{ view.headline }}</span>
              <span class="pulse-tile__cta">{{ view.cta || 'Shop' }}<span aria-hidden="true">&#8594;</span></span>
            </span>
            <span class="pulse-tile__art" [class.is-empty]="!view.products.length && !view.media">
              @if (view.media) {
                <cx-media [container]="view.media"></cx-media>
              } @else {
                @for (product of view.products; track product.code) {
                  <cx-media [container]="product.images?.['PRIMARY']" format="product" [alt]="''"></cx-media>
                }
              }
            </span>
          </cx-generic-link>
        }
        @case ('story') {
          <div class="pulse-story__inner">
            <h2>{{ view.headline }}</h2>
            <div class="pulse-rich" [innerHTML]="view.html"></div>
            @if (view.url) {
              <cx-generic-link [url]="view.url" class="pulse-btn pulse-btn--pop">
                {{ view.cta || 'Read more' }}<span aria-hidden="true">&#8594;</span>
              </cx-generic-link>
            }
          </div>
        }
        @case ('logo') {
          <cx-generic-link [url]="view.url || '/'" class="pulse-logo__link">
            <cx-media [container]="view.media"></cx-media>
          </cx-generic-link>
        }
        @default {
          <cx-generic-link [url]="view.url || '/'" class="pulse-plain__link">
            @if (view.media) {
              <cx-media [container]="view.media"></cx-media>
            }
            @if (view.headline) {
              <span class="pulse-plain__headline">{{ view.headline }}</span>
            }
            <span class="pulse-rich" [innerHTML]="view.html"></span>
          </cx-generic-link>
        }
      }
    }
  `,
})
export class PulseBannerComponent {
  private readonly component = inject<CmsComponentData<CmsBannerComponent>>(CmsComponentData);
  private readonly sanitizer = inject(DomSanitizer);
  private readonly categoryProducts = inject(ProductSearchByCategoryService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly renderer = inject(Renderer2);

  readonly view$: Observable<View> = this.component.data$.pipe(
    filter((data) => Boolean(data)),
    tap((data) => styleClasses(data).forEach((c) => this.renderer.addClass(this.host.nativeElement, c))),
    switchMap((data) => {
      const kind = pulseVariant(data, KINDS, 'plain');
      const { html, cta } = splitCallToAction(data.content);
      const view: View = {
        kind,
        headline: data.headline ?? '',
        html: this.sanitizer.bypassSecurityTrustHtml(html),
        cta,
        url: data.urlLink ?? '',
        media: data.media as MediaContainer | undefined,
        products: [],
      };
      const category = kind === 'tile' && !data.media ? /\/c\/([^/?#]+)/.exec(view.url)?.[1] : null;
      if (!category) {
        return of(view);
      }
      return this.categoryProducts.get({ categoryCode: decodeURIComponent(category), scope: PULSE_TILE_SCOPE }).pipe(
        map((products) => ({
          ...view,
          products: (products ?? []).filter((p) => p.images?.['PRIMARY']).slice(0, 3),
        })),
        catchError(() => of(view)),
        startWith(view),
      );
    }),
  );
}
