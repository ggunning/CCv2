import { NgModule } from '@angular/core';
import { translationChunksConfig, translationsEn } from '@spartacus/assets';
import {
  AuthConfig,
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
    // Sign-in for whichever site the URL picked. With `context: {}` the site's URL parameters are
    // only known after start-up, so Spartacus' 'auto' defaults would ask for the shared client
    // mobile_android_public and send the user back to the bare origin, which matches no site.
    // Always use the per-site client mobile_android_public_<site> and return to <origin>/<site>
    // (register that redirect URI, and give the site a path URL pattern /<site>).
    provideConfig(<AuthConfig>{
      authentication: {
        initializerOptions: {
          baseSiteSuffix: true,
          addBaseSiteToRedirectUri: true,
        },
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
      },
    }),
    provideConfig(defaultB2bOccConfig),
  ],
})
export class SpartacusConfigurationModule {}
