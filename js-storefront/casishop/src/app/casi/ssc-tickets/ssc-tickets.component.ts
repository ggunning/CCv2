import { DatePipe } from '@angular/common';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { GlobalMessageService, GlobalMessageType, OccEndpointsService, UserIdService } from '@spartacus/core';
import { Observable } from 'rxjs';
import { map, switchMap, take } from 'rxjs/operators';

/** One message of a ticket: the description first, then the notes that are not internal. */
interface TicketMessage {
  author: string;
  fromCustomer: boolean;
  text: string;
  createdOn?: string;
}

/** The document a ticket is about, e.g. { type: 'invoice', id: '90000004', label: 'Invoice 90000004' }. */
interface TicketReference {
  type: string;
  id: string;
  label: string;
}

/** A case in SAP Sales and Service Cloud, as returned by the casiserviceocc OCC extension. */
interface Ticket {
  /** UUID of the case: used in the API paths. */
  code: string;
  /** Case number: what people see and quote. */
  id: string;
  subject: string;
  type: string;
  typeName: string;
  /** Life cycle status: OPEN, IN_PROCESS, COMPLETED, CLOSED. */
  status: string;
  statusName: string;
  priorityName?: string;
  processor?: string;
  createdOn?: string;
  changedOn?: string;
  canReply: boolean;
  canClose: boolean;
  canReopen: boolean;
  reference?: TicketReference;
  messages?: TicketMessage[];
}

interface TicketType {
  code: string;
  name: string;
}

interface TicketList {
  account: { id: string; name: string };
  types: TicketType[];
  tickets: Ticket[];
}

const NEW = 'new';
/** The ticket type a ticket about a kind of document starts with, when that type is on offer. */
const TYPE_FOR: Record<string, string> = { invoice: 'ZINQ' };
/** ?about=invoice:90000004 */
const ABOUT = /^([a-z]{1,20}):([0-9A-Za-z_\-./]{1,40})$/;

/**
 * "Tickets" on the Customer Service page (see CasiServiceHubComponent). The tickets are the cases of
 * the customer's company in SAP Sales and Service Cloud, read and written live through the
 * casiserviceocc extension (core-customize/hybris/bin/custom/casiserviceocc):
 *   GET/POST /users/{userId}/ssctickets, GET /ssctickets/{code}, POST /ssctickets/{code}/messages,
 *   POST /ssctickets/{code}/status.
 * The query parameter "ticket" picks the view (none: list, "new": form, a code: that ticket), so the
 * browser's back button and links to a ticket work. With "new", the parameter "about"
 * (e.g. invoice:90000004) says which document the ticket is about.
 */
@Component({
  selector: 'casi-ssc-tickets',
  imports: [DatePipe, FormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './ssc-tickets.component.html',
})
export class CasiSscTicketsComponent {
  private readonly http = inject(HttpClient);
  private readonly occ = inject(OccEndpointsService);
  private readonly userIdService = inject(UserIdService);
  private readonly messages = inject(GlobalMessageService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  readonly newTicket = NEW;
  /** Query parameters of the link back to the list. */
  readonly toList = { ticket: null, about: null };

  private readonly params = toSignal(this.route.queryParamMap);

  /** Value of ?ticket=: null (list), "new" or a ticket code. */
  readonly selected = computed(() => this.params()?.get('ticket') ?? null);

  /** The document a new ticket is about, from ?about=type:id. */
  readonly about = computed<TicketReference | null>(() => {
    const match = ABOUT.exec(this.params()?.get('about') ?? '');
    if (!match) {
      return null;
    }
    const [, type, id] = match;
    return { type, id, label: `${type.charAt(0).toUpperCase()}${type.slice(1)} ${id}` };
  });

  readonly list = signal<TicketList | null>(null);
  readonly ticket = signal<Ticket | null>(null);
  readonly loading = signal(false);
  readonly failure = signal<string | null>(null);
  readonly sending = signal(false);

  /** Tickets opened in this visit: the list from Sales and Service Cloud shows a new case a few seconds late. */
  private readonly opened = signal<Ticket[]>([]);

  readonly tickets = computed(() => {
    const listed = this.list()?.tickets ?? [];
    const known = new Set(listed.map((ticket) => ticket.code));
    return [...this.opened().filter((ticket) => !known.has(ticket.code)), ...listed];
  });

  // New ticket form
  type = '';
  subject = '';
  description = '';
  // Reply form
  reply = '';

  constructor() {
    effect(() => {
      const selected = this.selected();
      untracked(() => {
        this.failure.set(null);
        if (selected && selected !== NEW) {
          this.loadTicket(selected);
        } else {
          this.loadList();
        }
      });
    });
    // A ticket about a document starts with a subject that names it and the type that fits it.
    effect(() => {
      const about = this.about();
      const types = this.list()?.types ?? [];
      untracked(() => {
        if (!about || this.selected() !== NEW) {
          return;
        }
        if (!this.subject) {
          this.subject = `Question about ${about.label.charAt(0).toLowerCase()}${about.label.slice(1)}`;
        }
        if (types.some((type) => type.code === TYPE_FOR[about.type])) {
          this.type = TYPE_FOR[about.type];
        }
      });
    });
  }

  canSubmit(): boolean {
    return !!this.type && !!this.subject.trim() && !!this.description.trim() && !this.sending();
  }

  submit(): void {
    if (!this.canSubmit()) {
      return;
    }
    this.sending.set(true);
    const about = this.about();
    const body = {
      type: this.type,
      subject: this.subject,
      message: this.description,
      ...(about ? { reference: { type: about.type, id: about.id } } : {}),
    };
    this.url('casiSscTickets')
      .pipe(switchMap((url) => this.http.post<Ticket>(url, body)))
      .subscribe({
        next: (created) => {
          this.sending.set(false);
          this.subject = '';
          this.description = '';
          this.opened.update((tickets) => [created, ...tickets]);
          this.ticket.set(created);
          this.messages.add(`Ticket ${created.id} opened.`, GlobalMessageType.MSG_TYPE_CONFIRMATION);
          this.show(created.code);
        },
        error: (error: HttpErrorResponse) => {
          this.sending.set(false);
          this.messages.add(this.text(error, 'The ticket could not be opened.'), GlobalMessageType.MSG_TYPE_ERROR);
        },
      });
  }

  send(ticket: Ticket): void {
    const message = this.reply.trim();
    if (!message || this.sending()) {
      return;
    }
    this.change(ticket, 'casiSscTicketMessages', { message }, 'Your message could not be sent.', () => (this.reply = ''));
  }

  /** "close": the customer has what they need. "reopen": a completed ticket needs more work. */
  setStatus(ticket: Ticket, action: 'close' | 'reopen'): void {
    if (this.sending()) {
      return;
    }
    this.change(ticket, 'casiSscTicketStatus', { action }, 'The ticket could not be changed.', () =>
      this.messages.add(
        `Ticket ${ticket.id} ${action === 'close' ? 'closed' : 'reopened'}.`,
        GlobalMessageType.MSG_TYPE_CONFIRMATION,
      ),
    );
  }

  /** Switch the view by setting or clearing ?ticket=; a view other than the form drops ?about=. */
  show(ticket: string | null): void {
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: ticket === NEW ? { ticket } : { ticket, about: null },
      queryParamsHandling: 'merge',
    });
  }

  private change(ticket: Ticket, endpoint: string, body: object, fallback: string, done: () => void): void {
    this.sending.set(true);
    this.url(endpoint, ticket.code)
      .pipe(switchMap((url) => this.http.post<Ticket>(url, body)))
      .subscribe({
        next: (updated) => {
          this.sending.set(false);
          this.ticket.set(updated);
          this.opened.update((tickets) => tickets.map((known) => (known.code === updated.code ? updated : known)));
          done();
        },
        error: (error: HttpErrorResponse) => {
          this.sending.set(false);
          this.messages.add(this.text(error, fallback), GlobalMessageType.MSG_TYPE_ERROR);
        },
      });
  }

  private loadList(): void {
    this.loading.set(!this.list());
    this.url('casiSscTickets')
      .pipe(switchMap((url) => this.http.get<TicketList>(url)))
      .subscribe({
        next: (list) => {
          this.loading.set(false);
          this.list.set(list);
          if (!this.type) {
            this.type = list.types[0]?.code ?? '';
          }
        },
        error: (error: HttpErrorResponse) => {
          this.loading.set(false);
          this.failure.set(this.text(error, 'Your tickets could not be loaded.'));
        },
      });
  }

  private loadTicket(code: string): void {
    if (this.ticket()?.code !== code) {
      this.ticket.set(null);
      this.reply = '';
    }
    this.loading.set(!this.ticket());
    this.url('casiSscTicket', code)
      .pipe(switchMap((url) => this.http.get<Ticket>(url)))
      .subscribe({
        next: (ticket) => {
          this.loading.set(false);
          // Ignore the answer when another ticket or the list was chosen meanwhile.
          if (this.selected() === code) {
            this.ticket.set(ticket);
          }
        },
        error: (error: HttpErrorResponse) => {
          this.loading.set(false);
          this.failure.set(this.text(error, 'This ticket could not be loaded.'));
        },
      });
  }

  private text(error: HttpErrorResponse, fallback: string): string {
    return error.error?.errors?.[0]?.message ?? fallback;
  }

  private url(endpoint: string, ticketCode?: string): Observable<string> {
    return this.userIdService.takeUserId(true).pipe(
      take(1),
      map((userId) => this.occ.buildUrl(endpoint, { urlParams: { userId, ticketCode } })),
    );
  }
}
