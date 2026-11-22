import { AsyncPipe, DatePipe } from '@angular/common';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  GlobalMessageService,
  GlobalMessageType,
  OccEndpointsService,
  UserIdService,
} from '@spartacus/core';
import { OrderDetailsService } from '@spartacus/order/components';
import { Order } from '@spartacus/order/root';
import { BehaviorSubject, combineLatest, Observable, of } from 'rxjs';
import { catchError, filter, map, switchMap, take } from 'rxjs/operators';

/** One S/4 customer return, as returned by the casireturnsocc OCC extension. */
interface S4Return {
  code: string;
  reference: string;
  created?: string;
  processStatus?: string;
  returnsStatus?: string;
  referencesOrder?: boolean;
  entries: { product: string; description?: string; quantity: string }[];
}

interface ReturnLine {
  product: string;
  name: string;
  ordered: number;
  quantity: number;
}

/** S/4 overall processing status (SD document status) in words. */
const PROCESS_STATUS: Record<string, string> = {
  A: 'Open',
  B: 'In process',
  C: 'Completed',
};

/**
 * "Returns" section on the S/4 order details page (CMS flex component CasiS4ReturnsComponent).
 * Orders in this shop live in SAP S/4HANA, so returns are S/4 customer returns created through
 * GET/POST /users/{userId}/s4returns (core-customize/hybris/bin/custom/casireturnsocc).
 */
@Component({
  selector: 'casi-s4-returns',
  imports: [AsyncPipe, DatePipe, FormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './s4-returns.component.html',
})
export class CasiS4ReturnsComponent {
  private readonly http = inject(HttpClient);
  private readonly occ = inject(OccEndpointsService);
  private readonly userIdService = inject(UserIdService);
  private readonly messages = inject(GlobalMessageService);
  private readonly orderDetails = inject(OrderDetailsService);

  private readonly refresh$ = new BehaviorSubject<void>(undefined);

  readonly order$: Observable<Order> = this.orderDetails
    .getOrderDetails()
    .pipe(filter((order): order is Order => !!order?.code));

  readonly returns$: Observable<S4Return[] | null> = combineLatest([this.order$, this.refresh$]).pipe(
    switchMap(([order]) =>
      this.url().pipe(
        switchMap((url) =>
          this.http.get<{ returns: S4Return[] }>(url, { params: { orderCode: order.code ?? '' } }),
        ),
        map((body) => body.returns ?? []),
        catchError(() => of(null)),
      ),
    ),
  );

  readonly formOpen = signal(false);
  readonly submitting = signal(false);
  lines: ReturnLine[] = [];

  statusText(code?: string): string {
    return (code && PROCESS_STATUS[code]) || code || '—';
  }

  openForm(order: Order): void {
    this.lines = (order.entries ?? [])
      .filter((entry) => entry.product?.code)
      .map((entry) => ({
        product: entry.product?.code ?? '',
        name: entry.product?.name ?? entry.product?.code ?? '',
        ordered: entry.quantity ?? 0,
        quantity: 0,
      }));
    this.formOpen.set(true);
  }

  hasSelection(): boolean {
    return this.lines.some((line) => line.quantity > 0);
  }

  submit(order: Order): void {
    const entries = this.lines
      .filter((line) => line.quantity > 0)
      .map((line) => ({ product: line.product, quantity: Math.min(line.quantity, line.ordered) }));
    if (!entries.length || this.submitting()) {
      return;
    }
    this.submitting.set(true);
    this.url()
      .pipe(switchMap((url) => this.http.post<S4Return>(url, { orderCode: order.code, entries })))
      .subscribe({
        next: (created) => {
          this.submitting.set(false);
          this.formOpen.set(false);
          this.messages.add(
            `Return ${created.code} requested in SAP S/4HANA.`,
            GlobalMessageType.MSG_TYPE_CONFIRMATION,
          );
          this.refresh$.next();
        },
        error: (error: HttpErrorResponse) => {
          this.submitting.set(false);
          const message = error.error?.errors?.[0]?.message ?? 'The return could not be requested.';
          this.messages.add(message, GlobalMessageType.MSG_TYPE_ERROR);
        },
      });
  }

  private url(): Observable<string> {
    return this.userIdService
      .takeUserId(true)
      .pipe(take(1), map((userId) => this.occ.buildUrl('casiS4Returns', { urlParams: { userId } })));
  }
}
