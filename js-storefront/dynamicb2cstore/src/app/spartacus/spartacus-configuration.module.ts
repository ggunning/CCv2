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
  ],
})
export class SpartacusConfigurationModule {}
