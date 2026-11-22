# CASI Shop on supernova — S/4HANA Cloud integration without CPI

B2B shop **CASI Shop** (site `casi-spa`, store `casi`) selling **Basic Ontology** (`CASI-ONTOLOGY`)
and its 1-year service plan (`CASI-ONTOLOGY-PLAN`), integrated directly with **SAP S/4HANA Cloud
BE6** (my4102318). Storefront: `js-storefront/casishop`.

## How it works

| Capability | Standard SAP or custom | How |
|---|---|---|
| Orders created in S/4 at checkout | SAP standard | SAP S/4HANA Order Management (`saps4omservices`, `saps4omfacades`, `saps4ombackoffice`, `saps4omocc`), synchronous order creation, OData V2 `API_SALES_ORDER_SRV` |
| Order history, details and status read live from S/4 | SAP standard | same module ("Enable Synchronous Order Creation and Order History") |
| Document flow (deliveries, invoices) on order details | SAP standard | destinations `s4omDocumentHeaderAPIDestination`, `s4omDocumentItemAPIDestination` |
| PDF invoices | SAP standard | `billingDestination`, `billingPDFDestination` (S/4 SAP_COM_0120); storefront `@spartacus/pdf-invoices` |
| Schedule lines, requested delivery date | SAP standard | `@spartacus/s4om`, `@spartacus/requested-delivery-date` |
| Request a return; list returns per order | **Custom** (S/4OM has no returns; SAP's returns need CPI) | extension `casireturnsocc`: `GET/POST /occ/v2/{site}/users/{user}/s4returns` → S/4 customer return (CBAR, `API_CUSTOMER_RETURN_SRV`); storefront component `CasiS4ReturnsComponent` on order details |

The S/4 Order Management module is **B2B only**, so the CASI Shop is a B2B site. The B2B unit uid is
the S/4 sold-to (`1000002`, "CASI Demo Company"); product codes are the S/4 product IDs.

## S/4HANA BE6 prerequisites (done)

Communication system `LANDSCAPE_COMMANDER` (inbound only, logical system `LC_CLI`), communication user
`LC_API_USER`, arrangements SAP_COM_0008, 0009, 0109, 0118, 0120, 0157. Customer `1000002`, products
`CASI-ONTOLOGY` (HAWA, plant DE10) and `CASI-ONTOLOGY-PLAN` (SERV, item category group NLAG).

Known BE6 configuration gaps (Central Business Configuration in BE0): the pricing procedure has no
price/tax condition (S/4 prices are 0, so the shop keeps Commerce prices), and no shipping point is
determined for DE10 (orders stay incomplete for delivery; returns then can't reference the order, so
`casireturnsocc` creates them unreferenced).

## Data scripts (apply through HAC, in this order)

Easiest: LandscapeCommander runs them in order on any environment, skips what is already there and
checks the result (works on d1, d2 and d3; nothing here is tied to one environment's hosts):

    python main.py casi status --env d1          # read-only; --env repeatable, default d1 d2 d3
    python main.py casi run --env d1 --dry-run
    python main.py casi run --env d1             # stops before 06 until the credential is set:
    python main.py casi credential --env d1      # run by a person: copies the S/4 password from .env

Sign-in is set up by `../sodastream-data/01_storefront_signin_and_smartedit.groovy` (step `signin`),
which reads this environment's storefront hosts itself. `08_storefront_signin.groovy` has the d3 host
hard-coded and is superseded by it.

| File | What |
|---|---|
| `01_catalog_products.impex` | `casiProductCatalog`, categories, both products, EUR prices, stock in `casi_warehouse` |
| `02_content_clone.groovy` | copy of the B2B powertools-spa CMS into `casiContentCatalog:Online` |
| `03_store_b2b.impex` | store `casi` (B2B checkout, ATP formula, warehouse delivery modes), B2B unit `1000002`, cost center, buyer `casi.buyer@example.com` (no password) |
| `04_site_solr.impex` | site `casi-spa` (Solr is all in 04b) |
| `04b_solr.groovy` | search config `casiIndex` saved as one graph; run again with `INDEX = true` to index |
| `05_casi_retarget.groovy` | CASI navigation, homepage hero, carousels, returns component on the order page |
| `06_s4_destinations.groovy` | the consumed destinations to S/4 (needs the credential below) |
| `07_s4om_store_config.groovy` | SAP base store configuration `casiS4Configuration` (S/4OM settings, destination mapping), `EUR.sapCode` |
| ~~`08_storefront_signin.groovy`~~ | superseded by `../sodastream-data/01_storefront_signin_and_smartedit.groovy` (d3 host hard-coded) |
| `09_checkout_smoke_test.groovy` | B2B checkout as the buyer through the checkout facades; `PLACE_ORDER = true` places an order in S/4 |
| `10_product_images.py` | product pictures "CASI Ontologie" and "CASI Helping Hands Service": one SVG per image format in `images/` (detail reduced for small sizes); `--upload` sends them to the tenant and re-indexes `casiIndex` |
| `11_occ_client.impex` | OCC API client `lc_occ_client` (client credentials, `ROLE_TRUSTED_CLIENT`) for scripted orders and invoice look-ups as the buyer; no secret in the file |

Passwords are never in these files. LandscapeCommander `python main.py casi credential --env <env>`, run by a
person, sets the S/4 credential `casiS4hcBE6Credential`, the buyer's password and the `lc_occ_client` secret
(`{env}_OCC_CLIENT_SECRET`, generated into `.env` on first use) from `.env`.

## Gotchas found on supernova

- CCv2's XSS filter strips request text like `src=...` from HAC calls: never name a Groovy variable
  `src`/`SRC`; send ImpEx base64-encoded through Groovy `importService` (LandscapeCommander helper).
- Groovy `sort {}` mutates; FlexibleSearch results are read-only — use `sort(false) {}`.
- Warehousing computes stock with the store's ATP formula and only counts warehouses that serve a
  delivery mode; without both, every product shows 0 / out of stock.
- A Solr config must be saved together with its indexed type and properties; index in a separate,
  later HAC run (indexer workers can't see uncommitted data).
- A Basic Credential can't be saved without a password.
