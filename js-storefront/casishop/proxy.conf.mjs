// Local development only (`npm start`): the dev server forwards the shop API, the media and the
// sign-in pages to a Commerce system, so the browser sees one origin and needs no CORS set-up.
// The system comes from the environment or from a one-line, git-ignored file next to this one:
//   OCC_TARGET=https://api.<environment domain> npm start
//   echo https://api.<environment domain> > .occ-target
// Customer self-service (casiserviceocc) can be tried before the extension is deployed: with
//   SSC_TICKETS_DEV=http://localhost:4201
// the ssctickets, selfservice and orgregistrations calls go to LandscapeCommander's stand-in
// (python -m casi.ssc_tickets_dev).
import { existsSync, readFileSync } from 'node:fs';

const file = new URL('./.occ-target', import.meta.url);
const target =
  process.env.OCC_TARGET ||
  (existsSync(file) ? readFileSync(file, 'utf-8').trim() : '') ||
  'https://localhost:9002';
const rule = { target, secure: false, changeOrigin: true };
const tickets = process.env.SSC_TICKETS_DEV
  ? {
      '^/occ/v2/[^/]+/users/[^/]+/(ssctickets|selfservice)': { target: process.env.SSC_TICKETS_DEV, changeOrigin: true },
      '^/occ/v2/[^/]+/orgregistrations': { target: process.env.SSC_TICKETS_DEV, changeOrigin: true },
    }
  : {};

export default {
  // first match wins: the stand-in before the general /occ rule
  ...tickets,
  '/occ': rule,
  '/medias': rule,
  // Spartacus joins the base URL and a CMS picture's path with two slashes (//medias/...);
  // a Commerce server accepts that, the dev server has to be told
  '^//medias': { ...rule, rewrite: (path) => path.replace(/^\/+/, '/') },
  // Sign-in: keep the server's redirects and cookies on the dev server's origin, so the sign-in form and
  // the saved authorize request share one session; the browser never talks to the system directly,
  // so its Origin header is not passed on (the server's CORS set-up does not matter locally)
  '/authorizationserver': {
    ...rule,
    autoRewrite: true,
    protocolRewrite: 'http',
    cookieDomainRewrite: '',
    configure: (proxy) => proxy.on('proxyReq', (proxyReq) => proxyReq.removeHeader('origin')),
  },
};
