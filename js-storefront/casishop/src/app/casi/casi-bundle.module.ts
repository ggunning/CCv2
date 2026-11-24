import { NgModule, inject, provideAppInitializer } from '@angular/core';
import { CartOutlets } from '@spartacus/cart/base/root';
import { OccConfig, provideConfig } from '@spartacus/core';
import { OutletPosition, ProductListOutlets, provideOutlet } from '@spartacus/storefront';
import { CasiBundleCartLineComponent } from './bundle-picker/bundle-cart-line.component';
import { CasiBundleCheckoutCheck } from './bundle-picker/bundle-checkout-check.service';
import { CasiBundleListActionComponent } from './bundle-picker/bundle-list-action.component';
import { CasiBundlePartComponent } from './bundle-picker/bundle-part.component';
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
    // product lists and grids: a bundle product says so and leads to its product page instead of add-to-cart
    provideOutlet({ id: ProductListOutlets.ITEM_ACTIONS, position: OutletPosition.BEFORE, component: CasiBundleListActionComponent }),
    // product code -> the bundle component the product starts
    {
      provide: CASI_BUNDLES,
      useValue: {
        'CASI-ONTOLOGY-ADV': {
          templateId: 'CASI_ADV_BUNDLE_ONTOLOGY',
          // its three plans are not sold on their own
          onlyInBundle: ['CASI-ADV-PLAN-BAS', 'CASI-ADV-PLAN-STD', 'CASI-ADV-PLAN-PRM'],
        },
      },
    },
    // product page of a plan: where to get it, instead of add-to-cart
    provideOutlet({ id: 'ProductAddToCartComponent', position: OutletPosition.BEFORE, component: CasiBundlePartComponent }),
    // cart: a bundle's lines go together: no "Remove" on the plan, "Remove bundle" on the product
    provideOutlet({ id: CartOutlets.ITEM_DETAILS, position: OutletPosition.AFTER, component: CasiBundleCartLineComponent }),
    // checkout: a cart that breaks a bundle (no plan, or a bundle product as an ordinary entry) goes back to the cart
    provideAppInitializer(() => inject(CasiBundleCheckoutCheck).start()),
    provideConfig(<OccConfig>{
      backend: {
        occ: {
          endpoints: {
            casiBundlePlans:
              'products/${productCode}/references?referenceType=SELECT&fields=references(target(code,name,summary,price(value,formattedValue)))',
            casiCartBundles: 'users/${userId}/carts/${cartId}/bundles',
            casiCartEntryGroups: 'users/${userId}/carts/${cartId}?fields=entryGroups(entryGroupNumber,entries(entryNumber),entryGroups(entryGroupNumber,entries(entryNumber)))',
            casiCartBundleCheck:
              'users/${userId}/carts/${cartId}?fields=entries(entryNumber,product(code,name)),entryGroups(entryGroupNumber,entryGroups(entryGroupNumber,entries(entryNumber)))',
            casiCartEntryGroup: 'users/${userId}/carts/${cartId}/entrygroups/${entryGroupNumber}',
          },
        },
      },
    }),
  ],
})
export class CasiBundleModule {}
