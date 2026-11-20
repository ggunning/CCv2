import { APP_BASE_HREF } from '@angular/common';
import {
  NgExpressEngineDecorator,
  defaultExpressErrorHandlers,
  getOriginValidationMiddleware,
  ngExpressEngine as engine,
} from '@spartacus/setup/ssr';
import express from 'express';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import bootstrap from './main.server';

const ngExpressEngine = NgExpressEngineDecorator.get(engine);

/**
 * Host names server-side rendering may serve. Angular refuses any other Host header
 * (protection against host-header spoofing) and falls back to client-side rendering.
 * The site itself is picked per request from the host (CMSSite.urlPatterns), so the
 * list decides which store domains this server renders. Set at start-up, no rebuild:
 * SSR_ALLOWED_HOSTS or NG_ALLOWED_HOSTS, comma-separated, wildcards like `*.example.com`.
 */
function ssrAllowedHosts(): string[] {
  const raw =
    process.env['SSR_ALLOWED_HOSTS'] ||
    process.env['NG_ALLOWED_HOSTS'] ||
    'localhost,*.localhost,*.cc.commerce.ondemand.com';
  return raw
    .split(',')
    .map((h) => h.trim())
    .filter(Boolean);
}

// The Express app is exported so that it can be used by serverless Functions.
export function app(): express.Express {
  const server = express();
  const serverDistFolder = dirname(fileURLToPath(import.meta.url));
  const browserDistFolder = resolve(serverDistFolder, '../browser');
  const indexHtml = join(serverDistFolder, 'index.server.html');
  const indexHtmlContent = readFileSync(indexHtml, 'utf-8');

  server.set('trust proxy', 'loopback');

  // Validates the resolved request origin against an allowlist. See the JSDoc
  // of `getOriginValidationMiddleware` for the accepted formats and matching
  // rules. Here it is read from the `SSR_ALLOWED_ORIGINS` environment variable
  // (comma-separated); when unset or empty the middleware is a no-op.
  server.use(
    getOriginValidationMiddleware({
      allowedOrigins: process.env['SSR_ALLOWED_ORIGINS'],
    }),
  );

  server.engine(
    'html',
    ngExpressEngine({
      bootstrap,
      allowedHosts: ssrAllowedHosts(),
    }),
  );

  server.set('view engine', 'html');
  server.set('views', browserDistFolder);

  // The bare host matches no CMSSite URL pattern, so rendering "/" fails ("Cannot get base site
  // config"). Send it to a default site instead; DEFAULT_SITE overrides it without a rebuild.
  const defaultSite = process.env['DEFAULT_SITE'] || 'electronics-pulse';
  server.get('/', (req, res, next) => {
    if (Object.keys(req.query).length) {
      next();
      return;
    }
    res.redirect(302, '/' + encodeURIComponent(defaultSite));
  });

  // Serve static files from /browser
  server.get(
    /.*\..*/,
    express.static(browserDistFolder, {
      maxAge: '1y',
    }),
  );

  // All regular routes use the Universal engine
  server.get(/.*/, (req, res) => {
    res.render(indexHtml, {
      req,
      providers: [{ provide: APP_BASE_HREF, useValue: req.baseUrl }],
    });
  });

  server.use(defaultExpressErrorHandlers(indexHtmlContent));

  return server;
}

function run() {
  const port = process.env['PORT'] || 4000;

  // Start up the Node server
  const server = app();
  server.listen(port, () => {
    console.log(`Node Express server listening on http://localhost:${port}`);
  });
}

run();
