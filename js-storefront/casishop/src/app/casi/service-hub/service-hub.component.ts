import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { OccEndpointsService, UserIdService } from '@spartacus/core';
import { map, switchMap, take } from 'rxjs/operators';
import { CasiSscTicketsComponent } from '../ssc-tickets/ssc-tickets.component';
import { CasiDocumentsComponent } from './documents.component';

interface DocumentKind {
  key: string;
  title: string;
}

const TICKETS = 'tickets';

/**
 * The Customer Service page of My Account (/my-account/support-tickets): tabs for the service
 * tickets and for each kind of document the casiserviceocc extension offers
 * (GET /users/{userId}/selfservice: invoices, returns, quotations, ...). The page's CMS flex component
 * "SupportTicketHistoryComponent" is this component in the CASI Shop, so no CMS change is needed.
 * The query parameter "view" picks the tab; "ticket" (a ticket or the new-ticket form) always shows
 * the tickets.
 */
@Component({
  selector: 'casi-service-hub',
  imports: [RouterLink, CasiSscTicketsComponent, CasiDocumentsComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './service-hub.component.html',
})
export class CasiServiceHubComponent {
  private readonly http = inject(HttpClient);
  private readonly occ = inject(OccEndpointsService);
  private readonly userIdService = inject(UserIdService);
  private readonly params = toSignal(inject(ActivatedRoute).queryParamMap);

  readonly tickets = TICKETS;
  readonly kinds = signal<DocumentKind[]>([]);

  /** "tickets" or the key of a kind of document. */
  readonly view = computed(() => {
    const params = this.params();
    return params?.get('ticket') ? TICKETS : (params?.get('view') ?? TICKETS);
  });

  readonly viewTitle = computed(() => this.kinds().find((kind) => kind.key === this.view())?.title ?? '');

  constructor() {
    this.userIdService
      .takeUserId(true)
      .pipe(
        take(1),
        map((userId) => this.occ.buildUrl('casiSelfService', { urlParams: { userId } })),
        switchMap((url) => this.http.get<{ kinds: DocumentKind[] }>(url)),
      )
      // Without the list of kinds the page still has its tickets.
      .subscribe({ next: (body) => this.kinds.set(body.kinds ?? []), error: () => this.kinds.set([]) });
  }
}
