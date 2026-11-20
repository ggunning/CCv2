import { CmsComponent } from '@spartacus/core';

/**
 * Pulse reuses the standard CMS types (banner, paragraph, product carousel) and tells its variants
 * apart by the component's style classes: a BannerComponent with `pulse-tile` is a category tile,
 * one with `pulse-hero` the home page hero. An editor picks the look in SmartEdit the same way.
 */
export function pulseVariant<T extends string>(
  data: CmsComponent | undefined,
  variants: readonly T[],
  fallback: T,
): T {
  const classes = ` ${data?.styleClasses ?? ''} `;
  return variants.find((v) => classes.includes(` pulse-${v} `)) ?? fallback;
}

/** The component's style classes, to put on its host element. */
export function styleClasses(data: CmsComponent | undefined): string[] {
  return (data?.styleClasses ?? '').split(/\s+/).filter(Boolean);
}

/** `Some text [[Button label]]` -> the text and the label. */
export function splitCallToAction(html: string | undefined): { html: string; cta: string } {
  const text = html ?? '';
  const match = /\[\[([^\]]{1,40})\]\]/.exec(text);
  return match
    ? { html: text.replace(match[0], '').trim(), cta: match[1].trim() }
    : { html: text, cta: '' };
}
