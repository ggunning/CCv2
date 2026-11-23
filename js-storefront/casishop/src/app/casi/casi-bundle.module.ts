import { NgModule } from '@angular/core';
import { OccConfig, provideConfig } from '@spartacus/core';
import { OutletPosition, provideOutlet } from '@spartacus/storefront';
import { CASI_BUNDLES, CasiBundlePickerComponent } from './bundle-picker/bundle-picker.component';

/**
 * Wires the plan picker for products sold as a configurable bundle (the Advanced Ontology with one of its
 * service plans): it is placed in front of the product page's add-to-cart (CMS flex component
 * "ProductAddToCartComponent"), so no CMS change is needed, and shows itself only for the products listed
 * under CASI_BUNDLES. The bundle and the plan references come from
 * core-customize/casi-data/16_advanced_bundle.groovy; the cart calls are configurablebundleocc's.
 */
@NgModule({
  providers: [
    provideOutlet({ id: 'ProductAddToCartComponent', position: OutletPosition.BEFORE, component: CasiBundlePickerComponent }),
    // product code -> the bundle component the product starts
    { provide: CASI_BUNDLES, useValue: { 'CASI-ONTOLOGY-ADV': { templateId: 'CASI_ADV_BUNDLE_ONTOLOGY' } } },
    provideConfig(<OccConfig>{
      backend: {
        occ: {
          endpoints: {
            casiBundlePlans:
              'products/${productCode}/references?referenceType=SELECT&fields=references(target(code,name,summary,price(value,formattedValue)))',
            casiCartBundles: 'users/${userId}/carts/${cartId}/bundles',
            casiCartEntryGroups: 'users/${userId}/carts/${cartId}?fields=entryGroups(entryGroupNumber,entries(entryNumber),entryGroups(entryGroupNumber,entries(entryNumber)))',
            casiCartEntryGroup: 'users/${userId}/carts/${cartId}/entrygroups/${entryGroupNumber}',
          },
        },
      },
    }),
  ],
})
export class CasiBundleModule {}
