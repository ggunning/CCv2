import { NgModule } from '@angular/core';
import { translationChunksConfig, translationsEn } from '@spartacus/assets';
import {
  FeaturesConfig,
  I18nConfig,
  OccConfig,
  provideConfig,
  provideConfigFactory,
  SiteContextConfig,
} from '@spartacus/core';
import {
  defaultCmsContentProviders,
  layoutConfigFactory,
  mediaConfig,
} from '@spartacus/storefront';

@NgModule({
  declarations: [],
  imports: [],
  providers: [
    provideConfigFactory(layoutConfigFactory),
    provideConfig(mediaConfig),
    ...defaultCmsContentProviders,
    provideConfig(<OccConfig>{
      backend: {
        occ: {
          //baseUrl: 'OCC_BACKEND_BASE_URL_VALUE',
          prefix: '/occ/v2/',
        },
      },
    }),
    // B2C shell: no fixed site list. Spartacus loads the base sites from OCC and picks the
    // one whose CMSSite.urlPatterns match the request URL (host such as allbirds1-spa.<domain>,
    // path /allbirds1-spa/... or ?site=...). Languages, currencies and URL encoding come from
    // that site, so a new B2C store needs an endpoint, not a rebuild. B2B sites go to dynamicb2bstore.
    provideConfig(<SiteContextConfig>{
      context: {},
    }),
    provideConfig(<I18nConfig>{
      i18n: {
        resources: { en: translationsEn },
        chunks: translationChunksConfig,
        fallbackLang: 'en',
      },
    }),
    provideConfig(<FeaturesConfig>{
      features: {
        level: '221121.20',
      },
    }),
  ],
})
export class SpartacusConfigurationModule {}
