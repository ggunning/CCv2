import { NgModule } from '@angular/core';
import { AuthGuard, CmsConfig, OccConfig, provideConfig } from '@spartacus/core';
import { OutletPosition, provideOutlet } from '@spartacus/storefront';
import { CasiApprovalBannerComponent } from './approval-banner/approval-banner.component';
import { CasiOrgRegistrationComponent } from './org-registration/org-registration.component';
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
 *
 * The same goes for the company registration: the shop's /login/register page has the flex component
 * "OrganizationUserRegistrationComponent", which here is the CASI registration form (company and contact
 * person, created in S/4HANA, Commerce and Sales and Service Cloud); Spartacus' own organization user
 * registration is not part of this storefront. Open to visitors, so no guard.
 */
@NgModule({
  providers: [
    // "Awaiting approval" banner under the header on every page (company not yet approved in S/4)
    provideOutlet({ id: 'cx-header', position: OutletPosition.AFTER, component: CasiApprovalBannerComponent }),
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
        OrganizationUserRegistrationComponent: {
          component: CasiOrgRegistrationComponent,
        },
        CasiOrgRegistrationComponent: {
          component: CasiOrgRegistrationComponent,
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
            casiOrgRegistrations: 'orgregistrations',
            casiApproval: 'users/${userId}/selfservice/approval',
          },
        },
      },
    }),
  ],
})
export class CasiTicketsModule {}
