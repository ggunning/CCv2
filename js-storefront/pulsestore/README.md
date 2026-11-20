# pulsestore

A Spartacus storefront with its own look ("Pulse"): running ticker, large type, category tiles
that show product pictures, product shelves, a buy box that stays in view. It is an extra next
to the other apps in this folder and changes none of them.

It renders sites whose CMS uses the Pulse page templates. CommerceCommander creates such a
site for an existing store (wizard *Pulse storefront*): a second site `<store>-pulse` with its
own content catalog on the same base store, so products, prices, stock and checkout are
shared. See `docs/PULSE_STOREFRONT.md` in the CommerceCommander repository.

Like `dynamicb2cstore` it has no fixed site: the site comes from the URL path
(`/<store>-pulse/...`), so a new Pulse site needs no rebuild.

## Where things are

| | |
|---|---|
| `src/app/pulse/pulse-layout.config.ts` | which slots each Pulse page template shows |
| `src/app/pulse/components/` | banner (hero, tile, story, logo), paragraph (ticker, theme), product shelf, listing headline, product title |
| `src/app/pulse/pulse.module.ts` | maps CMS component types to those components |
| `src/styles/pulse/` | the look: tokens, controls, header and footer, home, shop, flows |
| `src/app/spartacus/` | Spartacus set-up, as in `dynamicb2cstore` with the B2C features Pulse uses |

Template and slot names are shared with `commander/services/pulse.py` in CommerceCommander;
its tests compare the two.

## Run it locally

The dev server forwards `/occ`, `/medias` and `/authorizationserver` to a Commerce system
(`proxy.conf.mjs`), so no CORS set-up is needed.

```
echo https://<api host of an environment, or a DemoCloud host> > .occ-target
npm install
npm start
```

Open `http://localhost:4200/<store>-pulse/`.

## Deploy

The app is listed in `../manifest.json`. After a build and deployment, add a JS Storefront
endpoint in the Cloud Portal whose host starts with `pulsestore.`; CCv2 routes it to this app.
`DEFAULT_SITE` (environment variable) is the site the bare host redirects to.
