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
import { defaultB2bOccConfig } from '@spartacus/setup';
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
    // B2B shell: no fixed site list. Spartacus loads the base sites from OCC and picks the
    // one whose CMSSite.urlPatterns match the request URL (host such as powertools-spa.<domain>,
    // path /powertools-spa/... or ?site=...). B2B checkout and B2B OCC endpoints stay compiled
    // in, so only B2B sites should be routed to this app; B2C sites go to dynamicb2cstore.
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
    provideConfig(defaultB2bOccConfig),
  ],
})
export class SpartacusConfigurationModule {}
