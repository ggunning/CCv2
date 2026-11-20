import { NgModule } from '@angular/core';
import { CmsConfig, OccConfig, provideConfig } from '@spartacus/core';
import { PAGE_LAYOUT_HANDLER } from '@spartacus/storefront';
import { PULSE_TILE_SCOPE, PulseBannerComponent } from './components/pulse-banner.component';
import { PulseListingHeroComponent } from './components/pulse-listing-hero.component';
import { PulseParagraphComponent } from './components/pulse-paragraph.component';
import { PulseProductTitleComponent } from './components/pulse-product-title.component';
import { PulseShelfComponent } from './components/pulse-shelf.component';
import { PulseCartLayoutHandler } from './pulse-cart-layout.handler';
import { pulseLayoutConfig } from './pulse-layout.config';

/**
 * Everything that makes this app "Pulse" rather than the standard storefront:
 *  - layouts for the Pulse page templates;
 *  - its own components for banners, paragraphs and product carousels (everything else, from
 *    search to checkout, is the standard Spartacus component, restyled in src/styles/pulse);
 *  - the cart page's items-or-empty switch.
 *
 * Import it after the Spartacus feature modules: later configuration wins.
 */
@NgModule({
  providers: [
    provideConfig(pulseLayoutConfig),
    provideConfig(<CmsConfig>{
      cmsComponents: {
        BannerComponent: { component: PulseBannerComponent },
        SimpleBannerComponent: { component: PulseBannerComponent },
        SimpleResponsiveBannerComponent: { component: PulseBannerComponent },
        CMSParagraphComponent: { component: PulseParagraphComponent },
        ProductCarouselComponent: { component: PulseShelfComponent },
        PulseListingHeroComponent: { component: PulseListingHeroComponent },
        PulseProductTitleComponent: { component: PulseProductTitleComponent },
      },
    }),
    provideConfig(<OccConfig>{
      backend: {
        occ: {
          endpoints: {
            productSearchByCategory: {
              [PULSE_TILE_SCOPE]:
                'categories/${categoryCode}/products?fields=products(code,name,images(DEFAULT))&pageSize=6',
            },
          },
        },
      },
    }),
    { provide: PAGE_LAYOUT_HANDLER, useExisting: PulseCartLayoutHandler, multi: true },
  ],
})
export class PulseModule {}
