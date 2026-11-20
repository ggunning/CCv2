import { NgModule } from '@angular/core';
import { CmsConfig, provideConfig } from '@spartacus/core';
import {
  SMART_EDIT_FEATURE,
  SmartEditConfig,
  SmartEditRootModule,
} from '@spartacus/smartedit/root';

@NgModule({
  declarations: [],
  imports: [SmartEditRootModule],
  providers: [
    provideConfig(<CmsConfig>{
      featureModules: {
        [SMART_EDIT_FEATURE]: {
          module: () => import('@spartacus/smartedit').then((m) => m.SmartEditModule),
        },
      },
    }),
    provideConfig(<SmartEditConfig>{
      // Same build on d1, d2 and d3: SmartEdit runs on the Backoffice host of whichever
      // environment serves this app (backoffice.<subscription>-<env>-public...ondemand.com),
      // so allow any SAP Commerce Cloud host by wildcard, plus local SmartEdit for development.
      // A site's CMSSite.previewURL must end in /cx-preview for SmartEdit to attach.
      smartEdit: {
        storefrontPreviewRoute: 'cx-preview',
        allowOrigin: 'localhost:9002, *.ondemand.com',
      },
    }),
  ],
})
export class SmartEditFeatureModule {}
