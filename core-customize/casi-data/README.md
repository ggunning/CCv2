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
| Service tickets: list, open (also about an order, invoice or return), answer, close and reopen cases of the customer's company | **Custom** (SAP's ticket integration is for C4C V1 through CPI) | extension `casiserviceocc`: `GET/POST /occ/v2/{site}/users/{user}/ssctickets`, `GET .../ssctickets/{code}`, `POST .../ssctickets/{code}/messages`, `POST .../ssctickets/{code}/status` → cases in SAP Sales and Service Cloud V2 (REST `case-service`, live, nothing stored in Commerce); storefront: My Account > Customer Service (`CasiServiceHubComponent`) |
| Documents of the customer's company: invoices, returns, quotations (S/4), orders with the sales team (Sales and Service Cloud), registered products with their warranty and installations (Sales and Service Cloud); contracts and deliveries once S/4 allows it | **Custom** | extension `casiserviceocc`: `GET /occ/v2/{site}/users/{user}/selfservice` and `.../selfservice/{kind}` → live tables; storefront: tabs on the Customer Service page, each row can open a ticket about it |

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
| `12_ssc_tickets_destination.groovy` | consumed destination `casiSscCaseDestination` to Sales and Service Cloud (needs credential `casiSscCredential`; the host comes from `C360SSC_BASE_URL`) with the processor and case types for new tickets |
| `13_consent_templates.impex` | consent templates of the site `casi-spa` (`MARKETING_NEWSLETTER`, `STORE_USER_INFORMATION`): they belong to the site, so the content clone does not bring them and the cookie banner had nothing to ask |
| `14_site_setup.groovy` | what sample data gives a sample site and a hand-built one lacks: tax group `de-taxes` on the store and `eu-vat-full` on the products (VAT in cart and checkout), Backoffice groups on the product catalog with a Staged→Online sync job, `cmsmanagergroup` on the content catalog (no content sync job: the pages are cloned into Online, Staged is empty), full and update cron jobs for `casiIndex`, promotion group `casiPromoGrp` on the site |

Passwords are never in these files. LandscapeCommander `python main.py casi credential --env <env>`, run by a
person, sets the S/4 credential `casiS4hcBE6Credential`, the buyer's password and the `lc_occ_client` secret
(`{env}_OCC_CLIENT_SECRET`, generated into `.env` on first use) from `.env`.

## Customer self-service (extension `casiserviceocc`)

One page, My Account > Customer Service, with tabs: **Tickets** and one tab per kind of document.
Nothing is stored in Commerce; every call reads or writes the other system live.

**Who sees what.** The B2B unit uid is the S/4 customer number. S/4 documents are limited by
`SoldToParty` = that number; Sales and Service Cloud data by the account that carries the number as
an external ID. All buyers of a company see the company's tickets and documents.

**Tickets** are cases in Sales and Service Cloud. A ticket opened in the shop has origin
`SELF_SERVICE`; its description and every later message start with a line naming the buyer, and a
ticket opened from a document or an order also says `Regarding: Invoice 90000004`. Customers see the
description and the notes that are not internal (note type `S000`); internal comments (`S002`) stay
in Sales and Service Cloud. To answer a customer, an agent adds a normal (not internal) note.
"Close ticket" sets the case to Completed (`05`), "Reopen ticket" a completed case to In Process
(`02`); a closed case (`06`) stays closed.

**Documents** come from the list of sources in `SelfServiceController` (one entry per kind: where it
is read, how it is limited to the company, its columns). `casiserviceocc.documents` says which kinds
the shop offers:

| Kind | From | Needs |
|---|---|---|
| `products` | Sales and Service Cloud `registered-product-service` (serial number, product, warranty) | works |
| `installedbase` | Sales and Service Cloud `installed-base-service` | works |
| `invoices` | S/4 `API_BILLING_DOCUMENT_SRV` (`billingDestination`) | works |
| `returns` | S/4 `API_CUSTOMER_RETURN_SRV` (`casiCustomerReturnDestination`) | works |
| `quotations` | S/4 `API_SALES_QUOTATION_SRV` (`casiSalesQuotationDestination`) | works, no quotations in BE6 yet |
| `sscorders` | Sales and Service Cloud `sales-order-service` | works |
| `appointments` | Sales and Service Cloud `appointment-service` | works, off by default |
| `contracts` | S/4 `API_SALES_CONTRACT_SRV` (`casiSalesContractDestination`) | off: the S/4 communication user is not allowed yet; columns not verified |
| `deliveries` | S/4 `API_OUTBOUND_DELIVERY_SRV` (`casiOutboundDeliveryDestination`) | off: same |

A warranty has no customer of its own in Sales and Service Cloud: it shows as a column of the registered
product it is assigned to. Demo data (created 2026-11-22 over the API): warranty 1 "CASI 2-year product
warranty", installed base 1 "CASI Demo Company - Walldorf office", registered products `CASI-ONT-0001`
(with the warranty) and `CASI-ONT-0002`. These two services filter with `account/id eq '<id>'` (slash and
quotes) and want the ETag in `If-Match` without its quotes.

Not built, and why: warranty dates and which installation a product belongs to (no API field found for
either); service orders (the service is there, but no data, so the filter by company could not be
verified); service contracts, entitlements and a knowledge base (not on this tenant); ticket
attachments (the API user gets 403 on the document service); open items and prices (S/4 does not
allow the communication user; BE6 has no finance set-up).

Settings: `casiserviceocc/project.properties`; for the tickets the destination's additional
properties win (`processor`, `caseTypes`, `origin`, `priority`, `closeStatus`, `reopenStatus`). A
case cannot be saved without a processor, and the API user is no employee, so new cases go to the
configured one.

Set-up on an environment (after a build with `casiserviceocc` is deployed):

    python main.py casi credential --env d1      # run by a person: also copies C360SSC_API_USER/PASS
    python main.py casi run --env d1 --only destinations --only sscdest

No CMS change: the page `/my-account/support-tickets` keeps its flex component
`SupportTicketHistoryComponent`, which the casishop storefront maps to the service hub.

Try it before deploying: `python -m casi.ssc_tickets_dev --env d3` (LandscapeCommander) is a stand-in
with the same contract (`--self-test` checks tickets and documents without a shop);
`SSC_TICKETS_DEV=http://localhost:4201 npm start` in `js-storefront/casishop` sends the self-service
calls to it (see `proxy.conf.mjs`).

## Company registration (demo, extension `casiserviceocc`)

Not SAP standard: it shows how a shop could create a company and its contact person everywhere in one
go. Page `/login/register` (its flex component `OrganizationUserRegistrationComponent` is the CASI form
in casishop, so no CMS change) → `POST /occ/v2/{site}/orgregistrations` → `OrgRegistrationController`:

1. **S/4**: the company is looked up by VAT ID (`A_BusinessPartnerTaxNumber`); if it is not there it is
   created as a business partner with the customer roles, the sales area from
   `casiserviceocc.registration.*` and the VAT ID, and gets its customer number.
2. **Commerce**: B2B unit (uid = customer number) with address and cost center when new, and the B2B
   customer with the password chosen on the form.
3. **S/4**: the contact person (business partner, role `BUP001`) and a `BUR001` relationship to the company.
4. **Sales and Service Cloud**: the account with the customer number as external ID (types `sap.oitc.888`
   and `sap.oitc.918` in the S/4 communication system) when it is not there, and the contact person.

Steps 1 and 2 must work; a failure in 3 or 4 is reported in the answer (`steps`, `complete`) and does
not undo the registration. The same account then serves the tickets and documents above.

**Approval happens later in S/4.** A new company is created in S/4 with a delivery block (`01`) and a
billing block (`03`) on its customer, and its first person can sign in to the shop at once. They can
order and open tickets; S/4 takes the orders but does not deliver or invoice them. Someone approves the
company by removing both blocks in S/4 (Manage Customer Master Data). Until then the shop shows an
"awaiting approval" banner under the header, read live from S/4 (`GET /users/{user}/selfservice/approval`).
BE6 has no order block reasons, and a central business partner block does not stop orders, so these two
blocks are the approval. The codes are settings (`casiserviceocc.registration.deliveryBlock` / `billingBlock`).

The endpoint is public. Someone who joins a company that already exists in S/4 gets a login that stays disabled until it is
  enabled in Backoffice (User > Customers > Login disabled): a VAT ID is public, and the login shows
  that company's tickets and documents. The first person of a new company can sign in at once.

Needs: destination `casiBusinessPartnerDestination` (`06`), i.e. write access to `API_BUSINESS_PARTNER`
for the S/4 communication user (SAP_COM_0008), and the Sales and Service Cloud destination (`12`).
Known limits: a new S/4 customer has no company-code data in BE6, so ordering may stop in S/4; there is
no check that the VAT ID is real; test data cannot be deleted from S/4.

## Gotchas found on supernova

- CCv2's XSS filter strips request text like `src=...` from HAC calls: never name a Groovy variable
  `src`/`SRC`; send ImpEx base64-encoded through Groovy `importService` (LandscapeCommander helper).
- Groovy `sort {}` mutates; FlexibleSearch results are read-only — use `sort(false) {}`.
- Warehousing computes stock with the store's ATP formula and only counts warehouses that serve a
  delivery mode; without both, every product shows 0 / out of stock.
- A Solr config must be saved together with its indexed type and properties; index in a separate,
  later HAC run (indexer workers can't see uncommitted data).
- A Basic Credential can't be saved without a password.
