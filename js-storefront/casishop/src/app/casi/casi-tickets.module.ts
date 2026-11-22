import { NgModule } from '@angular/core';
import { AuthGuard, CmsConfig, OccConfig, provideConfig } from '@spartacus/core';
import { CasiServiceHubComponent } from './service-hub/service-hub.component';

/**
 * Wires the CASI customer self-service: the service tickets (cases in SAP Sales and Service Cloud)
 * and the company's documents from SAP S/4HANA and Sales and Service Cloud, through the OCC
 * endpoints of the casiserviceocc extension.
 *
 * The page is the one the shop already has: My Account > Customer Service
 * (/my-account/support-tickets), whose CMS flex component is "SupportTicketHistoryComponent". In the
 * CASI Shop that component is the service hub, so no CMS change is needed; Commerce's own tickets
 * (@spartacus/customer-ticketing) are not part of this storefront. A flex component with the type
 * "CasiServiceHubComponent" shows the same thing on any other page.
 */
@NgModule({
  providers: [
    provideConfig(<CmsConfig>{
      cmsComponents: {
        SupportTicketHistoryComponent: {
          component: CasiServiceHubComponent,
          guards: [AuthGuard],
        },
        CasiServiceHubComponent: {
          component: CasiServiceHubComponent,
          guards: [AuthGuard],
        },
      },
    }),
    provideConfig(<OccConfig>{
      backend: {
        occ: {
          endpoints: {
            casiSscTickets: 'users/${userId}/ssctickets',
            casiSscTicket: 'users/${userId}/ssctickets/${ticketCode}',
            casiSscTicketMessages: 'users/${userId}/ssctickets/${ticketCode}/messages',
            casiSscTicketStatus: 'users/${userId}/ssctickets/${ticketCode}/status',
            casiSelfService: 'users/${userId}/selfservice',
            casiSelfServiceKind: 'users/${userId}/selfservice/${kind}',
          },
        },
      },
    }),
  ],
})
export class CasiTicketsModule {}
