// Local development only (`npm start`): the dev server forwards the shop API, the media and the
// sign-in pages to a Commerce system, so the browser sees one origin and needs no CORS set-up.
// The system comes from the environment or from a one-line, git-ignored file next to this one:
//   OCC_TARGET=https://api.<environment domain> npm start
//   echo https://api.<environment domain> > .occ-target
import { existsSync, readFileSync } from 'node:fs';

const file = new URL('./.occ-target', import.meta.url);
const target =
  process.env.OCC_TARGET ||
  (existsSync(file) ? readFileSync(file, 'utf-8').trim() : '') ||
  'https://localhost:9002';
const rule = { target, secure: false, changeOrigin: true };

export default {
  '/occ': rule,
  '/medias': rule,
  // Spartacus joins the base URL and a CMS picture's path with two slashes (//medias/...);
  // a Commerce server accepts that, the dev server has to be told
  '^//medias': { ...rule, rewrite: (path) => path.replace(/^\/+/, '/') },
  '/authorizationserver': rule,
};
