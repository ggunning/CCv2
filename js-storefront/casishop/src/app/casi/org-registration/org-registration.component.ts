import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { OccEndpointsService } from '@spartacus/core';

interface RegistrationStep {
  /** Where it happened: S/4HANA, Commerce, Sales and Service Cloud. */
  system: string;
  result: string;
  ok: boolean;
}

/** Answer of POST /orgregistrations (casiserviceocc extension). */
interface RegistrationResult {
  company: { number: string; name: string; isNew: boolean };
  email: string;
  /** False when the person joined an existing company: someone has to enable the sign-in first. */
  loginEnabled: boolean;
  /** True when the company waits for approval in S/4 (delivery and billing blocked until then). */
  approvalPending: boolean;
  /** False when a later step (contact in S/4, Sales and Service Cloud) did not work. */
  complete: boolean;
  steps: RegistrationStep[];
}

/** The countries the CASI store delivers to. */
const COUNTRIES = [
  { code: 'DE', name: 'Germany' },
  { code: 'AT', name: 'Austria' },
  { code: 'CH', name: 'Switzerland' },
  { code: 'FR', name: 'France' },
  { code: 'NL', name: 'Netherlands' },
  { code: 'BE', name: 'Belgium' },
  { code: 'GB', name: 'United Kingdom' },
];

/**
 * "Register your company" (the shop's /login/register page; its CMS flex component
 * "OrganizationUserRegistrationComponent" is this component in the CASI Shop). One form for the
 * company and its contact person; the casiserviceocc extension creates both in SAP S/4HANA, in
 * Commerce and in SAP Sales and Service Cloud and answers with what happened in each system.
 * A demo of how this can work, not SAP standard.
 */
@Component({
  selector: 'casi-org-registration',
  imports: [FormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './org-registration.component.html',
})
export class CasiOrgRegistrationComponent {
  private readonly http = inject(HttpClient);
  private readonly occ = inject(OccEndpointsService);

  readonly countries = COUNTRIES;
  readonly sending = signal(false);
  readonly failure = signal<string | null>(null);
  /** Steps that were done before a failure, if any. */
  readonly failedSteps = signal<RegistrationStep[]>([]);
  readonly result = signal<RegistrationResult | null>(null);

  company = { name: '', street: '', houseNumber: '', postalCode: '', city: '', country: 'DE', vatId: '' };
  contact = { firstName: '', lastName: '', email: '' };
  password = '';

  canSubmit(): boolean {
    const filled = [
      this.company.name,
      this.company.street,
      this.company.postalCode,
      this.company.city,
      this.contact.firstName,
      this.contact.lastName,
      this.contact.email,
    ].every((value) => value.trim());
    return filled && this.password.length >= 10 && !this.sending();
  }

  submit(): void {
    if (!this.canSubmit()) {
      return;
    }
    this.sending.set(true);
    this.failure.set(null);
    this.failedSteps.set([]);
    const body = { password: this.password, company: this.company, contact: this.contact };
    this.http.post<RegistrationResult>(this.occ.buildUrl('casiOrgRegistrations'), body).subscribe({
      next: (result) => {
        this.sending.set(false);
        this.password = '';
        this.result.set(result);
      },
      error: (error: HttpErrorResponse) => {
        this.sending.set(false);
        this.failure.set(error.error?.errors?.[0]?.message ?? 'The registration did not work. Please try again.');
        this.failedSteps.set(error.error?.steps ?? []);
      },
    });
  }
}
