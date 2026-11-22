import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { AuthService, OccEndpointsService, UserIdService } from '@spartacus/core';
import { of } from 'rxjs';
import { catchError, map, switchMap, take } from 'rxjs/operators';

/**
 * Banner under the header while the signed-in customer's company waits for approval in SAP S/4HANA
 * (its S/4 customer still has a delivery or billing block; see casiserviceocc
 * GET /users/{userId}/selfservice/approval). Read once per page load and each time the customer signs in,
 * so it disappears after the blocks are removed in S/4 and the shop is reloaded. Shows nothing when signed
 * out, when the company is approved, or when S/4 cannot be asked.
 */
@Component({
  selector: 'casi-approval-banner',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (pending()) {
      <div class="casi-approval-banner" role="status">
        <p>
          <strong>Your company account is awaiting approval.</strong>
          You can already order and open service tickets. We deliver and invoice once your company is confirmed.
        </p>
      </div>
    }
  `,
})
export class CasiApprovalBannerComponent {
  private readonly http = inject(HttpClient);
  private readonly occ = inject(OccEndpointsService);
  private readonly userIdService = inject(UserIdService);

  readonly pending = toSignal(
    inject(AuthService)
      .isUserLoggedIn()
      .pipe(
        switchMap((signedIn) =>
          !signedIn
            ? of(false)
            : this.userIdService.takeUserId(true).pipe(
                take(1),
                switchMap((userId) =>
                  this.http.get<{ pending: boolean }>(
                    this.occ.buildUrl('casiApproval', { urlParams: { userId } }),
                  ),
                ),
                map((body) => body.pending === true),
                catchError(() => of(false)),
              ),
        ),
      ),
    { initialValue: false },
  );
}
