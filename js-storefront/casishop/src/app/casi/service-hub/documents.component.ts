import { DatePipe } from '@angular/common';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, effect, inject, input, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { OccEndpointsService, UserIdService } from '@spartacus/core';
import { map, switchMap, take } from 'rxjs/operators';

interface DocumentColumn {
  key: string;
  label: string;
  /** text, date (yyyy-MM-dd) or datetime (ISO). */
  type: 'text' | 'date' | 'datetime';
}

/** One kind of document of the customer's company, as returned by the casiserviceocc OCC extension. */
interface DocumentList {
  key: string;
  title: string;
  /** What a ticket about one of these is called ("invoice", "return", ...), if tickets can be about them. */
  referenceType?: string;
  columns: DocumentColumn[];
  documents: { id: string; values: Record<string, string> }[];
}

/**
 * One tab of the Customer Service page (see CasiServiceHubComponent): a kind of document the
 * customer's company has in SAP S/4HANA or SAP Sales and Service Cloud (invoices, returns,
 * quotations, ...), read live through GET /users/{userId}/selfservice/{kind} of the casiserviceocc
 * extension. The extension decides the columns; this component only draws them. Each row can start
 * a ticket about that document.
 */
@Component({
  selector: 'casi-documents',
  imports: [DatePipe, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './documents.component.html',
})
export class CasiDocumentsComponent {
  private readonly http = inject(HttpClient);
  private readonly occ = inject(OccEndpointsService);
  private readonly userIdService = inject(UserIdService);

  /** Key of the kind to show, e.g. "invoices". */
  readonly kind = input.required<string>();
  /** Title while the list is loading (the tab's label). */
  readonly title = input('');

  readonly list = signal<DocumentList | null>(null);
  readonly loading = signal(false);
  readonly failure = signal<string | null>(null);

  constructor() {
    effect(() => {
      const kind = this.kind();
      untracked(() => this.load(kind));
    });
  }

  private load(kind: string): void {
    this.list.set(null);
    this.failure.set(null);
    this.loading.set(true);
    this.userIdService
      .takeUserId(true)
      .pipe(
        take(1),
        map((userId) => this.occ.buildUrl('casiSelfServiceKind', { urlParams: { userId, kind } })),
        switchMap((url) => this.http.get<DocumentList>(url)),
      )
      .subscribe({
        next: (list) => {
          // Ignore the answer when another tab was chosen meanwhile.
          if (this.kind() === kind) {
            this.loading.set(false);
            this.list.set(list);
          }
        },
        error: (error: HttpErrorResponse) => {
          if (this.kind() === kind) {
            this.loading.set(false);
            this.failure.set(error.error?.errors?.[0]?.message ?? 'This could not be loaded.');
          }
        },
      });
  }
}
