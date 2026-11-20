import { AsyncPipe, DOCUMENT } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  HostListener,
  Renderer2,
  inject,
} from '@angular/core';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { Router } from '@angular/router';
import { CmsParagraphComponent } from '@spartacus/core';
import { CmsComponentData } from '@spartacus/storefront';
import { Observable } from 'rxjs';
import { filter, map, tap } from 'rxjs/operators';
import { pulseVariant, styleClasses } from '../pulse-variant';

type Kind = 'ticker' | 'theme' | 'html';
interface View {
  kind: Kind;
  html: SafeHtml;
}

const KINDS: readonly Kind[] = ['ticker', 'theme'];
const COLOUR = /^#[0-9a-f]{6}$/i;

/**
 * CMSParagraphComponent, three ways:
 *  - `pulse-ticker`: the spans of the paragraph run as an endless strip above the header;
 *  - `pulse-theme`: no output; `accent:#rrggbb pop:#rrggbb` become the site's colours, so the
 *    brand colour is CMS content and needs no rebuild;
 *  - anything else: the HTML as written (the other pulse-* classes are styled in the stylesheet).
 */
@Component({
  selector: 'pulse-paragraph',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AsyncPipe],
  template: `
    @if (view$ | async; as view) {
      @switch (view.kind) {
        @case ('ticker') {
          <div class="pulse-ticker__track">
            <div class="pulse-ticker__run" [innerHTML]="view.html"></div>
            <div class="pulse-ticker__run" aria-hidden="true" [innerHTML]="view.html"></div>
          </div>
        }
        @case ('html') {
          <div class="pulse-rich" [innerHTML]="view.html"></div>
        }
      }
    }
  `,
})
export class PulseParagraphComponent {
  private readonly component = inject<CmsComponentData<CmsParagraphComponent>>(CmsComponentData);
  private readonly sanitizer = inject(DomSanitizer);
  private readonly router = inject(Router);
  private readonly document = inject(DOCUMENT);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly renderer = inject(Renderer2);

  readonly view$: Observable<View> = this.component.data$.pipe(
    filter((data) => Boolean(data)),
    tap((data) => styleClasses(data).forEach((c) => this.renderer.addClass(this.host.nativeElement, c))),
    map((data) => {
      const kind = pulseVariant(data, KINDS, 'html');
      if (kind === 'theme') {
        this.setColours(data.content ?? '');
      }
      return { kind, html: this.sanitizer.bypassSecurityTrustHtml(data.content ?? '') };
    }),
  );

  /** Links written in the CMS stay inside the app (and keep the site, language and currency). */
  @HostListener('click', ['$event'])
  handleClick(event: Event): void {
    const anchor = (event.target as HTMLElement | null)?.closest?.('a');
    const href = anchor?.getAttribute('href');
    if (!anchor || !href || anchor.target === '_blank') {
      return;
    }
    const page = new URL(anchor.ownerDocument.URL);
    if (anchor.host === page.host) {
      event.preventDefault();
      this.router.navigateByUrl(href.replace(page.origin, ''));
    }
  }

  /** Server-side too, so the first paint already has the brand colour. */
  private setColours(tokens: string): void {
    const root = this.document.documentElement;
    for (const pair of tokens.replace(/<[^>]*>/g, ' ').split(/\s+/)) {
      const [name, value] = pair.split(':');
      if ((name === 'accent' || name === 'pop') && COLOUR.test(value ?? '')) {
        root.style.setProperty(`--pulse-${name}`, value);
      }
    }
  }
}
