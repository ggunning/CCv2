// CASI 14: the set-up a sample site gets from its sample data and a hand-built site does not.
// Found by comparing casi-spa with powertools-spa (same result on d1, d2 and d3). Run after 01-04b.
// Run in HAC > Console > Scripting with commit ON. Re-runnable: every part only adds what is missing.
//
//  1 Tax        store casi gets a user tax group, the products a product tax group: without both
//               Commerce calculates no VAT in cart and checkout. Tax rows are per catalog version, so
//               the CASI catalog gets its own; the rate comes from the SAP sample tax of the same groups.
//  2 Catalogs   product catalog: Staged->Online sync job and the usual Backoffice groups, so product
//               managers can maintain and publish CASI products. Content catalog: read and write for
//               cmsmanagergroup, but NO sync job on purpose: 02 clones the pages straight into Online
//               and Staged is empty, so there is nothing to publish from Staged yet.
//  3 Search     full and update cron jobs for casiIndex with triggers, like the sample indexes have:
//               without them a changed product reaches the search only when someone indexes by hand.
//  4 Promotions a promotion group of its own as the site's default, so promotions can be made for
//               the CASI Shop without touching the sample sites' groups.

def STORE = 'casi'
def SITE = 'casi-spa'
def PRODUCT_CATALOG = 'casiProductCatalog'
def CONTENT_CATALOG = 'casiContentCatalog'
def USER_TAX_GROUP = 'de-taxes'        // German VAT, the store sells from Germany
def PRODUCT_TAX_GROUP = 'eu-vat-full'  // full rate for both products
def INDEX = 'casiIndex'
def FULL_INDEX_CRON = '0 30 3 * * ?'   // every night
def UPDATE_INDEX_CRON = '0 0/5 * * * ?' // every five minutes
def PROMOTION_GROUP = 'casiPromoGrp'
def PRODUCT_READERS = ['employeegroup', 'backofficeproductmanagergroup', 'backofficeworkflowusergroup', 'marketingManagerGroup']
def PRODUCT_WRITERS = ['backofficeproductmanagergroup', 'backofficeworkflowusergroup']
def CONTENT_GROUP = 'cmsmanagergroup'

def fs = { String q, Map p = [:] -> flexibleSearchService.search(q, p).result }
def ctx = de.hybris.platform.core.Registry.getCoreApplicationContext()
def store = baseStoreService.getBaseStoreForUid(STORE)
def site = baseSiteService.getBaseSiteForUID(SITE)
if (!store || !site) { println "STOP store ${STORE} or site ${SITE} is missing: run 03 and 04 first"; return }

// --- 1 tax ----------------------------------------------------------------------------------------
def userTaxGroup = fs("SELECT {pk} FROM {UserTaxGroup} WHERE {code}=?c", [c: USER_TAX_GROUP])[0]
def productTaxGroup = fs("SELECT {pk} FROM {ProductTaxGroup} WHERE {code}=?c", [c: PRODUCT_TAX_GROUP])[0]
// Tax rows are per catalog version: the sample rows only cover the sample catalogs. Take the tax (the
// rate) from a sample row of the same two groups and make a row for each CASI catalog version.
def sampleRow = userTaxGroup && productTaxGroup ? fs("SELECT {pk} FROM {TaxRow} WHERE {ug}=?u AND {pg}=?p", [u: userTaxGroup, p: productTaxGroup])[0] : null
if (!sampleRow) {
  println "FAIL tax: no tax row for ${USER_TAX_GROUP} / ${PRODUCT_TAX_GROUP} on this environment (SAP sample data missing)"
} else {
  def utg = de.hybris.platform.europe1.enums.UserTaxGroup.valueOf(USER_TAX_GROUP)
  def ptg = de.hybris.platform.europe1.enums.ProductTaxGroup.valueOf(PRODUCT_TAX_GROUP)
  if (store.taxGroup != utg) { store.taxGroup = utg; modelService.save(store) }
  def taxRows = []
  ['Staged', 'Online'].each { version ->
    def cv = catalogVersionService.getCatalogVersion(PRODUCT_CATALOG, version)
    def row = fs("SELECT {pk} FROM {TaxRow} WHERE {ug}=?u AND {pg}=?p AND {catalogVersion}=?cv", [u: userTaxGroup, p: productTaxGroup, cv: cv])[0]
    if (!row) {
      row = modelService.create('TaxRow')
      row.ug = utg; row.pg = ptg; row.tax = sampleRow.tax; row.catalogVersion = cv
      modelService.save(row)
    }
    taxRows << row
  }
  def changed = 0
  fs("SELECT {p.pk} FROM {Product AS p JOIN CatalogVersion AS v ON {p.catalogVersion}={v.pk} JOIN Catalog AS c ON {v.catalog}={c.pk}} WHERE {c.id}=?id", [id: PRODUCT_CATALOG]).each { p ->
    if (p.europe1PriceFactory_PTG != ptg) { p.europe1PriceFactory_PTG = ptg; modelService.save(p); changed++ }
  }
  println "tax: store ${STORE} -> ${USER_TAX_GROUP}, " + taxRows.size() + ' tax rows ' + sampleRow.tax.code + ' ' + sampleRow.tax.value + "% for ${PRODUCT_CATALOG}, ${changed} products set to ${PRODUCT_TAX_GROUP}"
}

// --- 2 catalogs -----------------------------------------------------------------------------------
def principals = { List uids -> uids.collect { uid -> fs("SELECT {pk} FROM {Principal} WHERE {uid}=?u", [u: uid])[0] }.findAll { it } }
def grant = { String catalog, String version, List readers, List writers ->
  def cv = catalogVersionService.getCatalogVersion(catalog, version)
  def read = ((cv.readPrincipals ?: []) + principals(readers)).unique()
  def write = ((cv.writePrincipals ?: []) + principals(writers)).unique()
  if (read.size() != (cv.readPrincipals ?: []).size() || write.size() != (cv.writePrincipals ?: []).size()) {
    cv.readPrincipals = read; cv.writePrincipals = write; modelService.save(cv)
  }
  println "catalogs: ${catalog}:${version} read " + read*.uid.sort().join(',') + ' / write ' + write*.uid.sort().join(',')
}
grant(PRODUCT_CATALOG, 'Staged', PRODUCT_READERS, PRODUCT_WRITERS)
grant(PRODUCT_CATALOG, 'Online', PRODUCT_READERS, PRODUCT_WRITERS)
grant(CONTENT_CATALOG, 'Staged', [CONTENT_GROUP], [CONTENT_GROUP])
// Online is where the pages are (see above), so content managers need to write there
grant(CONTENT_CATALOG, 'Online', [CONTENT_GROUP], [CONTENT_GROUP])
def syncJobs = { fs("SELECT {j.pk} FROM {CatalogVersionSyncJob AS j JOIN CatalogVersion AS v ON {j.sourceVersion}={v.pk} JOIN Catalog AS c ON {v.catalog}={c.pk}} WHERE {c.id}=?id", [id: PRODUCT_CATALOG]) }
if (!syncJobs()) ctx.getBean('setupSyncJobService').createProductCatalogSyncJob(PRODUCT_CATALOG)
println 'catalogs: sync job ' + (syncJobs()*.code.join(', ') ?: 'NOT created')
if (!syncJobs()) println "FAIL catalogs: no sync job for ${PRODUCT_CATALOG}"

// --- 3 search -------------------------------------------------------------------------------------
def config = fs("SELECT {pk} FROM {SolrFacetSearchConfig} WHERE {name}=?n", [n: INDEX])[0]
if (!config) {
  println "FAIL search: no ${INDEX}: run 04b first"
} else {
  def like = fs("SELECT {pk} FROM {SolrIndexerCronJob} WHERE {job} IS NOT NULL ORDER BY {pk}")[0]
  def job = like?.job ?: fs("SELECT {pk} FROM {ServicelayerJob} WHERE {code}='solrIndexerJob'")[0]
  if (!job) {
    println 'FAIL search: no solrIndexerJob on this environment'
  } else {
    def ops = de.hybris.platform.solrfacetsearch.enums.IndexerOperationValues
    [['full', ops.FULL, FULL_INDEX_CRON], ['update', ops.UPDATE, UPDATE_INDEX_CRON]].each { d ->
      def code = d[0] + '-' + INDEX + '-cronJob'
      def cron = fs("SELECT {pk} FROM {SolrIndexerCronJob} WHERE {code}=?c", [c: code])[0]
      if (!cron) {
        cron = modelService.create('SolrIndexerCronJob')
        cron.code = code; cron.job = job; cron.facetSearchConfig = config; cron.indexerOperation = d[1]
        cron.singleExecutable = false; cron.active = true
        cron.sessionLanguage = commonI18NService.getLanguage('en')
        cron.sessionCurrency = store.defaultCurrency
        cron.sessionUser = like?.sessionUser ?: userService.getAdminUser()
        modelService.save(cron)
      }
      if (!cron.triggers) {
        def trigger = modelService.create('Trigger')
        trigger.cronJob = cron; trigger.cronExpression = d[2]; trigger.active = true
        modelService.save(trigger)
        modelService.refresh(cron)
      }
      println "search: ${code} " + cron.triggers.collect { it.cronExpression + (it.active ? '' : ' (inactive)') }.join(', ')
    }
  }
}

// --- 4 promotions ---------------------------------------------------------------------------------
def group = fs("SELECT {pk} FROM {PromotionGroup} WHERE {Identifier}=?i", [i: PROMOTION_GROUP])[0]
if (!group) {
  group = modelService.create('PromotionGroup')
  group.identifier = PROMOTION_GROUP
  modelService.save(group)
}
if (site.defaultPromotionGroup == null) { site.defaultPromotionGroup = group; modelService.save(site) }
println "promotions: site ${SITE} -> " + site.defaultPromotionGroup.identifier
