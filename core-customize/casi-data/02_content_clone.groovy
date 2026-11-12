// CASI Shop — clone the B2B powertools-spa CMS (Online) into casiContentCatalog:Online.
// Same recipe as the Skydoo store (LandscapeCommander docs/skydoo/CMS_CLONE.md): clone straight
// into Online (catalog sync doesn't copy CMS), in dependency order, rewiring references by uid.
// Re-runnable: items that already exist in the target (same uid) are reused, not cloned again.
// Run in HAC > Console > Scripting with commit ON. Note: CCv2's XSS filter strips text like
// 'src = ...' from the request, so never name a variable src/SRC in HAC scripts. Set STAGE to run one step at a time if needed.
import de.hybris.platform.catalog.model.CatalogVersionModel
import de.hybris.platform.cms2.model.contents.ContentCatalogModel

def FROM_CATALOG = 'powertools-spaContentCatalog'
def TGT = 'casiContentCatalog'
def STAGE = 'all'   // all | catalog | templates | components | slots | pages | bindings | nav | dedupe

def fromCv = catalogVersionService.getCatalogVersion(FROM_CATALOG, 'Online')
def log = []
def errors = []

// --- target catalog + versions ------------------------------------------------
def tgtCatalog = flexibleSearchService.search("SELECT {pk} FROM {ContentCatalog} WHERE {id}=?id", [id: TGT]).result[0]
if (!tgtCatalog) {
  tgtCatalog = modelService.create(ContentCatalogModel)
  tgtCatalog.id = TGT
  tgtCatalog.setName('CASI Content Catalog', Locale.ENGLISH)
  modelService.save(tgtCatalog)
  log << 'created catalog ' + TGT
}
def versionOf = { String v ->
  def cv = flexibleSearchService.search("SELECT {pk} FROM {CatalogVersion} WHERE {catalog}=?c AND {version}=?v", [c: tgtCatalog, v: v]).result[0]
  if (!cv) {
    cv = modelService.create(CatalogVersionModel)
    cv.catalog = tgtCatalog; cv.version = v; cv.active = (v == 'Online')
    cv.languages = [commonI18NService.getLanguage('en')]
    modelService.save(cv)
    log << 'created version ' + v
  }
  cv
}
versionOf('Staged')
def tgt = versionOf('Online')

// --- helpers --------------------------------------------------------------------
def inVersion = { String type, cv -> flexibleSearchService.search("SELECT {pk} FROM {" + type + "} WHERE {catalogVersion}=?cv", [cv: cv]).result }
def byUid = { String type, cv -> inVersion(type, cv).collectEntries { [(it.uid): it] } }
def runs = { String s -> STAGE == 'all' || STAGE == s }
def cloneInto = { item, Closure prepare ->
  def copy = modelService.clone(item)
  copy.catalogVersion = tgt
  prepare(copy)
  modelService.save(copy)
  copy
}

// 1. page templates (recreated: uid, name, frontend template, page types) ---------
if (runs('templates')) {
  def have = byUid('PageTemplate', tgt)
  inVersion('PageTemplate', fromCv).each { t ->
    if (have[t.uid]) return
    try {
      def n = modelService.create(t.getClass())
      n.uid = t.uid; n.name = t.name; n.frontendTemplateName = t.frontendTemplateName
      n.active = t.active; n.restrictedPageTypes = t.restrictedPageTypes; n.catalogVersion = tgt
      modelService.save(n)
    } catch (e) { errors << 'template ' + t.uid + ': ' + e.message }
  }
  log << 'templates: ' + inVersion('PageTemplate', tgt).size()
}

// 2. components --------------------------------------------------------------------
if (runs('components')) {
  def have = byUid('AbstractCMSComponent', tgt)
  inVersion('AbstractCMSComponent', fromCv).each { c ->
    if (have[c.uid]) return
    try { cloneInto(c) { } } catch (e) { errors << 'component ' + c.uid + ': ' + e.message }
  }
  // container children point at the clones (tab containers, rotating banners, ...)
  def clones = byUid('AbstractCMSComponent', tgt)
  clones.values().each { c ->
    ['simpleCMSComponents', 'banners', 'components'].each { attr ->
      try {
        def value = modelService.getAttributeValue(c, attr)
        if (value instanceof Collection && value) {
          def mapped = value.collect { v -> v?.hasProperty('uid') && clones[v.uid] ? clones[v.uid] : v }
          if (mapped != value) { modelService.setAttributeValue(c, attr, mapped); modelService.save(c) }
        }
      } catch (ignored) { }
    }
  }
  log << 'components: ' + clones.size()
}

// 3. slots (cmsComponents rewired to the clones, de-duplicated) ----------------------
if (runs('slots')) {
  def comps = byUid('AbstractCMSComponent', tgt)
  def have = byUid('ContentSlot', tgt)
  inVersion('ContentSlot', fromCv).each { s ->
    if (have[s.uid]) return
    try {
      cloneInto(s) { copy -> copy.cmsComponents = s.cmsComponents.collect { comps[it.uid] }.findAll { it != null }.unique { it.uid } }
    } catch (e) { errors << 'slot ' + s.uid + ': ' + e.message }
  }
  log << 'slots: ' + inVersion('ContentSlot', tgt).size()
}

// 4. pages (template rewired, preview image dropped: its cloned media clashes) -------
if (runs('pages')) {
  def templates = byUid('PageTemplate', tgt)
  def have = byUid('AbstractPage', tgt)
  inVersion('AbstractPage', fromCv).each { p ->
    if (have[p.uid]) return
    try {
      cloneInto(p) { copy -> copy.masterTemplate = templates[p.masterTemplate?.uid]; copy.previewImage = null }
    } catch (e) { errors << 'page ' + p.uid + ': ' + e.message }
  }
  log << 'pages: ' + inVersion('AbstractPage', tgt).size()
}

// 5. slot bindings for templates and pages ----------------------------------------
if (runs('bindings')) {
  def templates = byUid('PageTemplate', tgt)
  def pages = byUid('AbstractPage', tgt)
  def slots = byUid('ContentSlot', tgt)
  def haveT = byUid('ContentSlotForTemplate', tgt)
  inVersion('ContentSlotForTemplate', fromCv).each { b ->
    if (haveT[b.uid]) return
    try { cloneInto(b) { copy -> copy.pageTemplate = templates[b.pageTemplate.uid]; copy.contentSlot = slots[b.contentSlot.uid] } }
    catch (e) { errors << 'template binding ' + b.uid + ': ' + e.message }
  }
  def haveP = byUid('ContentSlotForPage', tgt)
  inVersion('ContentSlotForPage', fromCv).each { b ->
    if (haveP[b.uid]) return
    try { cloneInto(b) { copy -> copy.page = pages[b.page.uid]; copy.contentSlot = slots[b.contentSlot.uid] } }
    catch (e) { errors << 'page binding ' + b.uid + ': ' + e.message }
  }
  log << 'bindings: ' + inVersion('ContentSlotForTemplate', tgt).size() + ' template, ' + inVersion('ContentSlotForPage', tgt).size() + ' page'
}

// 6. navigation nodes + entries, and components' navigationNode ------------------------
if (runs('nav')) {
  def have = byUid('CMSNavigationNode', tgt)
  def srcNodes = inVersion('CMSNavigationNode', fromCv)
  srcNodes.each { n ->
    if (have[n.uid]) return
    try { cloneInto(n) { copy -> copy.parent = null; copy.children = []; copy.entries = [] } }
    catch (e) { errors << 'nav node ' + n.uid + ': ' + e.message }
  }
  def nodes = byUid('CMSNavigationNode', tgt)
  srcNodes.each { n ->
    def copy = nodes[n.uid]
    if (copy && n.parent && nodes[n.parent.uid] && copy.parent?.uid != n.parent.uid) { copy.parent = nodes[n.parent.uid]; modelService.save(copy) }
  }
  def comps = byUid('AbstractCMSComponent', tgt)
  def pages = byUid('AbstractPage', tgt)
  def haveE = byUid('CMSNavigationEntry', tgt)
  inVersion('CMSNavigationEntry', fromCv).each { e ->
    if (haveE[e.uid]) return
    try {
      cloneInto(e) { copy ->
        copy.navigationNode = nodes[e.navigationNode?.uid]
        def target = e.item?.hasProperty('uid') ? (comps[e.item.uid] ?: pages[e.item.uid]) : null
        if (target) copy.item = target
      }
    } catch (ex) { errors << 'nav entry ' + e.uid + ': ' + ex.message }
  }
  comps.values().each { c ->
    try {
      def node = modelService.getAttributeValue(c, 'navigationNode')
      if (node && nodes[node.uid] && node.catalogVersion != tgt) { modelService.setAttributeValue(c, 'navigationNode', nodes[node.uid]); modelService.save(c) }
    } catch (ignored) { }
  }
  log << 'nav nodes: ' + nodes.size() + ', entries: ' + inVersion('CMSNavigationEntry', tgt).size()
}

// 7. de-duplicate slot component lists (re-runs must never multiply components) -------
if (runs('dedupe')) {
  def fixed = 0
  inVersion('ContentSlot', tgt).each { s ->
    def unique = s.cmsComponents.unique(false) { it.uid }
    if (unique.size() != s.cmsComponents.size()) { s.cmsComponents = unique; modelService.save(s); fixed++ }
  }
  log << 'deduped slots: ' + fixed
}

println log.join('\n')
println 'errors: ' + errors.size()
errors.take(30).each { println '  ' + it }
