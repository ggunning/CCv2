import { LayoutConfig } from '@spartacus/storefront';

/**
 * Layouts for the Pulse page templates (created by CommerceCommander's "Pulse storefront" wizard,
 * commander/services/pulse.py). The names are the PageTemplate uids and the slot positions of that
 * CMS: change them in both places.
 *
 * The three sections at the top apply to every page; a template overrides them where it wants a
 * quieter page (sign-in and checkout keep only the logo and the cart).
 */
const quiet = {
  header: { slots: ['PulseTheme', 'SiteLogo', 'MiniCart'] },
  navigation: { slots: [] },
  footer: { slots: ['Footer'] },
};

export const pulseLayoutConfig: LayoutConfig = {
  layoutSlots: {
    header: {
      lg: {
        slots: [
          'PulseTheme',
          'PulseTicker',
          'SiteLogo',
          'NavigationBar',
          'SearchBox',
          'SiteContext',
          'SiteLogin',
          'MiniCart',
        ],
      },
      slots: ['PulseTheme', 'PulseTicker', 'PreHeader', 'SiteLogo', 'SearchBox', 'MiniCart'],
    },
    navigation: {
      lg: { slots: [] },
      slots: ['SiteLogin', 'NavigationBar', 'SiteContext'],
    },
    footer: {
      slots: ['PulsePerks', 'Footer'],
    },
    PulseHomeTemplate: {
      pageFold: 'PulseBento',
      slots: ['PulseHero', 'PulseBento', 'PulseShelfA', 'PulseStory', 'PulseShelfB'],
    },
    PulseListingTemplate: {
      slots: ['PulseListingHero', 'PulseFilters', 'PulseGrid'],
    },
    PulseProductTemplate: {
      pageFold: 'PulseDetails',
      slots: ['PulseGallery', 'PulseBuyBox', 'PulseDetails', 'PulseRelated'],
    },
    PulseCartTemplate: {
      slots: ['PulseCartItems', 'PulseCartSummary', 'PulseCartEmpty'],
    },
    PulseAuthTemplate: {
      ...quiet,
      slots: ['PulseAuthMain', 'PulseAuthSide'],
    },
    PulseAccountTemplate: {
      slots: ['PulseAccountNav', 'BodyContent'],
    },
    PulseCheckoutTemplate: {
      ...quiet,
      slots: ['BodyContent', 'SideContent'],
    },
    PulseContentTemplate: {
      slots: ['PulseContentHero', 'BodyContent'],
    },
  },
};
