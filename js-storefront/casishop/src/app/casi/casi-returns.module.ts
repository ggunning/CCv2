import { NgModule } from '@angular/core';
import { AuthGuard, CmsConfig, OccConfig, provideConfig } from '@spartacus/core';
import { CasiS4ReturnsComponent } from './s4-returns/s4-returns.component';

/**
 * Wires the CASI returns section: the CMS flex component "CasiS4ReturnsComponent" on the
 * order details page, and the OCC endpoint of the casireturnsocc extension.
 */
@NgModule({
  providers: [
    provideConfig(<CmsConfig>{
      cmsComponents: {
        CasiS4ReturnsComponent: {
          component: CasiS4ReturnsComponent,
          guards: [AuthGuard],
        },
      },
    }),
    provideConfig(<OccConfig>{
      backend: {
        occ: {
          endpoints: {
            casiS4Returns: 'users/${userId}/s4returns',
          },
        },
      },
    }),
  ],
})
export class CasiReturnsModule {}
