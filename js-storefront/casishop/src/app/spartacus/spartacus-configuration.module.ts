import { DOCUMENT } from '@angular/common';
import { inject, isDevMode, NgModule } from '@angular/core';
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
    // `npm start` only: the dev server proxies /occ and /medias (proxy.conf.mjs), so the API is on
    // the page's own origin. Deployed, CCv2 puts the real API host into index.html's meta tag.
    provideConfigFactory(() =>
      isDevMode() ? <OccConfig>{ backend: { occ: { baseUrl: inject(DOCUMENT).location.origin } } } : {},
    ),
    provideConfig(<SiteContextConfig>{
      context: {
        currency: ['EUR'],
        language: ['en'],
        baseSite: ['casi-spa'],
        urlParameters: ['baseSite', 'language', 'currency'],
      },
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
        // SAP S/4HANA Order Management integration (integration pack 2211.15+):
        // sort the S/4 billing documents on the order details page by invoice date.
        pdfInvoicesSortByInvoiceDate: true,
      },
    }),
    provideConfig(defaultB2bOccConfig),
  ],
})
export class SpartacusConfigurationModule {}
