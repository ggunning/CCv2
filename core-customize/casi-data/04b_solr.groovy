// CASI Shop — Solr search config casiIndex, built as ONE graph and saved with saveAll: the
// SolrFacetSearchConfig validator needs the indexed type + properties present at save time, which a
// row-by-row ImpEx can't give (see LandscapeCommander docs/skydoo/SETUP.md). Enum values (property
// types, facet types, query types, wildcard type) are taken from the live powertoolsIndex.
// Free-text template: fts* switches TRUE (boosts alone emit no queries), boosts = powertoolsIndex.
// Then: store casi -> casiIndex, cache invalidated, full index. Run in HAC scripting, commit ON.

def NAME = 'casiIndex'
def pt = flexibleSearchService.search("SELECT {pk} FROM {SolrFacetSearchConfig} WHERE {name}='powertoolsIndex'").result[0]
def ptType = pt.solrIndexedTypes[0]
def propType = ptType.solrIndexedProperties.collectEntries { [(it.type.code): it.type] }
def facetType = ptType.solrIndexedProperties.findAll { it.facetType }.collectEntries { [(it.facetType.code.toLowerCase()): it.facetType] }
def sortableText = ptType.solrIndexedProperties.find { it.sortableType?.code == 'sortabletext' }?.sortableType
def queryType = ptType.solrIndexerQueries.collectEntries { [(it.type.code.toLowerCase()): it.type] }
def ptTemplate = flexibleSearchService.search("SELECT {pk} FROM {SolrSearchQueryTemplate} WHERE {indexedType}=?t AND {name}='DEFAULT'", [t: ptType]).result[0]
def postfix = ptTemplate.searchQueryProperties.find { it.ftsWildcardQueryType }?.ftsWildcardQueryType

def cfg = flexibleSearchService.search("SELECT {pk} FROM {SolrFacetSearchConfig} WHERE {name}=?n", [n: NAME]).result[0]
if (!cfg) {
  def casiOnline = catalogVersionService.getCatalogVersion('casiProductCatalog', 'Online')
  cfg = modelService.create('SolrFacetSearchConfig')
  cfg.name = NAME; cfg.description = 'CASI Shop search'; cfg.indexNamePrefix = 'casi'
  cfg.solrServerConfig = pt.solrServerConfig; cfg.solrSearchConfig = pt.solrSearchConfig; cfg.solrIndexConfig = pt.solrIndexConfig
  cfg.languages = [commonI18NService.getLanguage('en')]
  cfg.currencies = [commonI18NService.getCurrency('EUR')]
  cfg.catalogVersions = [casiOnline]

  def type = modelService.create('SolrIndexedType')
  type.identifier = 'casiProductType'; type.type = typeService.getComposedTypeForCode('Product'); type.variant = false
  type.solrFacetSearchConfig = cfg
  cfg.solrIndexedTypes = [type]

  // name, type, provider, provider parameter, facet type, multiValue, localized, currency, sortable
  def defs = [
    ['itemtype', 'string', null, null, null, false, false, false, false],
    ['code', 'string', 'springELValueProvider', 'code', null, false, false, false, false],
    ['name', 'text', 'springELValueProvider', 'getName(#lang)', null, false, true, false, true],
    ['description', 'text', 'springELValueProvider', 'getDescription(#lang)', null, false, true, false, false],
    ['summary', 'text', 'springELValueProvider', 'getSummary(#lang)', null, false, true, false, false],
    ['ean', 'string', 'springELValueProvider', 'ean', null, false, false, false, false],
    ['keywords', 'text', 'productKeywordsValueProvider', null, null, false, true, false, false],
    ['priceValue', 'double', 'productPriceValueProvider', null, null, false, false, true, false],
    ['price', 'double', 'productPriceValueProvider', null, 'multiselector', false, false, true, false],
    ['img-515Wx515H', 'string', 'image515ValueProvider', null, null, false, false, false, false],
    ['img-300Wx300H', 'string', 'image300ValueProvider', null, null, false, false, false, false],
    ['img-96Wx96H', 'string', 'image96ValueProvider', null, null, false, false, false, false],
    ['img-65Wx65H', 'string', 'image65ValueProvider', null, null, false, false, false, false],
    ['img-30Wx30H', 'string', 'image30ValueProvider', null, null, false, false, false, false],
    ['url', 'string', 'productUrlValueProvider', null, null, false, true, false, false],
    ['stockLevelStatus', 'string', 'productStockLevelStatusValueProvider', null, null, false, false, false, false],
    ['inStockFlag', 'boolean', 'productInStockFlagValueProvider', null, null, false, false, false, false],
    ['allCategories', 'string', 'categoryCodeValueProvider', null, null, true, false, false, false],
    ['categoryName', 'text', 'categoryNameValueProvider', null, null, true, true, false, false],
    ['category', 'string', 'categoryCodeValueProvider', null, 'refine', true, false, false, false],
    ['categoryPath', 'string', 'categoryPathValueProvider', null, 'refine', true, false, false, false],
  ]
  def props = defs.collect { d ->
    def p = modelService.create('SolrIndexedProperty')
    p.solrIndexedType = type; p.name = d[0]; p.type = propType[d[1]]
    if (d[2]) p.fieldValueProvider = d[2]
    if (d[3]) p.valueProviderParameter = d[3]
    p.facet = d[4] != null
    if (d[4]) p.facetType = facetType[d[4]] ?: facetType['refine']
    p.multiValue = d[5]; p.localized = d[6]; p.currency = d[7]
    if (d[8] && sortableText) p.sortableType = sortableText
    p
  }
  type.solrIndexedProperties = props

  def sql = "SELECT {p:PK} FROM {Product AS p JOIN CatalogVersion AS cv ON {p:catalogVersion}={cv:PK} JOIN Catalog AS c ON {cv:catalog}={c:PK}} WHERE {c:id}='casiProductCatalog' AND {cv:version}='Online'"
  def queries = [['casi-fullQuery', 'full', sql], ['casi-updateQuery', 'update', sql + " AND {p:modifiedtime} >= ?lastIndexTime"]].collect { q ->
    def iq = modelService.create('SolrIndexerQuery')
    iq.solrIndexedType = type; iq.identifier = q[0]; iq.type = queryType[q[1]]; iq.query = q[2]
    iq.injectCurrentDate = true; iq.injectCurrentTime = true; iq.injectLastIndexTime = true
    iq
  }
  type.solrIndexerQueries = queries

  def template = modelService.create('SolrSearchQueryTemplate')
  template.name = 'DEFAULT'; template.indexedType = type; template.ftsQueryBuilder = 'defaultFreeTextQueryBuilder'
  def byName = props.collectEntries { [(it.name): it] }
  // property, query/boost, phrase/boost, fuzzy/boost, wildcard/boost/min length
  def fts = [
    ['code', false, null, true, 90f, true, null, true, 45f, 3],
    ['ean', false, null, true, 100f, true, null, true, 50f, 3],
    ['name', true, 50f, true, 100f, true, 25f, false, null, null],
    ['description', true, 25f, true, 50f, true, 10f, false, null, null],
    ['summary', true, 20f, true, 40f, true, 10f, false, null, null],
    ['keywords', true, 20f, true, 40f, true, 10f, false, null, null],
    ['categoryName', true, 20f, true, 40f, true, 10f, false, null, null],
  ]
  def templateProps = fts.collect { f ->
    def sp = modelService.create('SolrSearchQueryProperty')
    sp.indexedProperty = byName[f[0]]; sp.searchQueryTemplate = template
    sp.ftsQuery = f[1]; sp.ftsQueryBoost = f[2]
    sp.ftsPhraseQuery = f[3]; sp.ftsPhraseQueryBoost = f[4]
    sp.ftsFuzzyQuery = f[5]; sp.ftsFuzzyQueryBoost = f[6]
    sp.ftsWildcardQuery = f[7]; sp.ftsWildcardQueryBoost = f[8]
    if (f[9]) sp.ftsWildcardQueryMinTermLength = f[9]
    if (postfix) sp.ftsWildcardQueryType = postfix
    sp
  }
  template.searchQueryProperties = templateProps

  modelService.saveAll([cfg, type] + props + queries + [template] + templateProps)
  println "created ${NAME}: ${props.size()} properties, ${templateProps.size()} search fields"
} else {
  println "${NAME} exists"
}

def store = baseStoreService.getBaseStoreForUid('casi')
store.solrFacetSearchConfiguration = cfg
modelService.save(store)
println 'store casi -> ' + store.solrFacetSearchConfiguration.name

// Indexing runs in worker threads that can't see uncommitted data: index in a SECOND run
// (set INDEX = true) after this one has committed.
def INDEX = false
if (INDEX) {
  def ctx = de.hybris.platform.core.Registry.getCoreApplicationContext()
  ctx.getBean('defaultFacetSearchConfigCacheService').invalidate(NAME)
  def facetCfg = ctx.getBean('defaultFacetSearchConfigService').getConfiguration(NAME)
  ctx.getBean('defaultIndexerService').performFullIndex(facetCfg)
  println 'full index done'
}
