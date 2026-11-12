import { NgModule } from '@angular/core';
import { CmsConfig, I18nConfig, provideConfig } from '@spartacus/core';
import {
  pdfInvoicesTranslationChunksConfig,
  pdfInvoicesTranslationsEn,
} from '@spartacus/pdf-invoices/assets';
import { PDF_INVOICES_FEATURE, PDFInvoicesRootModule } from '@spartacus/pdf-invoices/root';

/** S/4 billing documents (PDF invoices) on the order details page — Invoice OCC API. */
@NgModule({
  imports: [PDFInvoicesRootModule],
  providers: [
    provideConfig(<CmsConfig>{
      featureModules: {
        [PDF_INVOICES_FEATURE]: {
          module: () => import('@spartacus/pdf-invoices').then((m) => m.PDFInvoicesModule),
        },
      },
    }),
    provideConfig(<I18nConfig>{
      i18n: {
        resources: { en: pdfInvoicesTranslationsEn },
        chunks: pdfInvoicesTranslationChunksConfig,
        fallbackLang: 'en',
      },
    }),
  ],
})
export class PdfInvoicesFeatureModule {}
