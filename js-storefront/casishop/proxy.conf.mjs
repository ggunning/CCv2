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
  // the saved authorize request share one session; the browser never talks to the system directly
  '/authorizationserver': {
    ...rule,
    autoRewrite: true,
    protocolRewrite: 'http',
    cookieDomainRewrite: '',
    configure: (proxy) => {
      // The browser's own Origin (this dev server) is never passed on: it is not in the server's CORS list,
      // and with any Origin it does not list the server answers "Invalid CORS request" (authorize, the
      // sign-in form's post, token). One request needs an Origin: the sign-in form's token request (/csrf)
      // is answered only for the origin of the client's login page, which for a local shop is
      // https://localhost (see below); without it the server says "Login page configuration does not
      // match the request".
      proxy.on('proxyReq', (proxyReq, req) => {
        if (/^\/authorizationserver\/csrf(\?|$)/.test(req.url ?? '')) {
          proxyReq.setHeader('origin', 'https://localhost');
        } else {
          proxyReq.removeHeader('origin');
        }
      });
      // The sign-in client's login page is "https://{redirectUriHost}/login": for a local shop the server
      // makes that https://localhost/login (no port, https), which nothing serves. Keep it on this dev
      // server instead. The system must list localhost among its allowed login page hosts
      // (authserver.oauthclientdetails.loginpageuri.allowed.hosts): d1 and d2 do, d3 does not.
      proxy.on('proxyRes', (proxyRes) => {
        const to = proxyRes.headers.location;
        if (to && /^https?:\/\/localhost(?=\/|\?|$)/.test(to)) {
          proxyRes.headers.location = to.replace(/^https?:\/\/localhost/, '') || '/';
        }
      });
    },
  },
};
