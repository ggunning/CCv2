import { NgModule } from '@angular/core';
import { BaseStorefrontModule } from '@spartacus/storefront';
import { SpartacusConfigurationModule } from './spartacus-configuration.module';
import { SpartacusFeaturesModule } from './spartacus-features.module';
import { PulseModule } from '../pulse/pulse.module';

@NgModule({
  declarations: [],
  // PulseModule last: its layouts and component mapping override the standard ones
  imports: [BaseStorefrontModule, SpartacusFeaturesModule, SpartacusConfigurationModule, PulseModule],
  exports: [BaseStorefrontModule],
})
export class SpartacusModule {}
