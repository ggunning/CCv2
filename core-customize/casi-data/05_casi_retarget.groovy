// CASI Shop — turn the cloned powertools CMS (casiContentCatalog:Online) into the CASI Shop.
//  1. category navigation: Ontologies / Service plans / All products
//  2. homepage: CASI hero paragraph first, stock banners removed from the homepage's own slots
//  3. every product carousel shows the CASI products
//  4. order details page: add the CMS flex component CasiS4ReturnsComponent (returns, casireturnsocc)
// Re-runnable. Run in HAC scripting with commit ON (after 02_content_clone.groovy).
import de.hybris.platform.cms2.model.contents.components.CMSParagraphComponentModel
import de.hybris.platform.cms2.model.contents.components.CMSLinkComponentModel
import de.hybris.platform.cms2.model.navigation.CMSNavigationNodeModel
import de.hybris.platform.cms2.model.navigation.CMSNavigationEntryModel
import de.hybris.platform.cms2.model.contents.components.CMSFlexComponentModel

def cv = catalogVersionService.getCatalogVersion('casiContentCatalog', 'Online')
def productCv = catalogVersionService.getCatalogVersion('casiProductCatalog', 'Online')
def EN = Locale.ENGLISH
def out = []
def find = { String type, String uid -> flexibleSearchService.search("SELECT {pk} FROM {" + type + "} WHERE {uid}=?u AND {catalogVersion}=?cv", [u: uid, cv: cv]).result[0] }
def inCv = { String type -> flexibleSearchService.search("SELECT {pk} FROM {" + type + "} WHERE {catalogVersion}=?cv", [cv: cv]).result }
def upsert = { Class cls, String type, String uid, Closure fill ->
  def item = find(type, uid) ?: modelService.create(cls)
  item.uid = uid; item.catalogVersion = cv
  fill(item); modelService.save(item); item
}

// 1. category navigation --------------------------------------------------------------
def root = upsert(CMSNavigationNodeModel, 'CMSNavigationNode', 'CasiCategoryNavNode') { n -> n.setName('CASI categories'); n.setTitle('CASI categories', EN) }
[['Ontologies', 'casi-ontologies'], ['Service plans', 'casi-services'], ['All products', 'casi-all']].each { title, code ->
  def key = code.replace('casi-', '').capitalize()
  def link = upsert(CMSLinkComponentModel, 'CMSLinkComponent', 'Casi' + key + 'Link') { l -> l.name = title; l.setLinkName(title, EN); l.url = '/c/' + code }
  def node = upsert(CMSNavigationNodeModel, 'CMSNavigationNode', 'Casi' + key + 'NavNode') { n -> n.setName(title); n.setTitle(title, EN); n.parent = root }
  upsert(CMSNavigationEntryModel, 'CMSNavigationEntry', 'Casi' + key + 'NavEntry') { e -> e.name = title; e.navigationNode = node; e.item = link }
}
inCv('CategoryNavigationComponent').each { c -> c.navigationNode = root; c.name = 'CASI Category Navigation'; modelService.save(c); out << 'category nav -> CasiCategoryNavNode: ' + c.uid }

// 2. homepage hero ----------------------------------------------------------------------
def hero = upsert(CMSParagraphComponentModel, 'CMSParagraphComponent', 'CasiHeroParagraph') { p ->
  p.name = 'CASI hero'
  p.setContent('<div class="casi-hero"><h1>Basic Ontology: one shared language for your business</h1>' +
    '<p>Customers, products, orders and returns, modelled once and used everywhere. Ships as a boxed set with ' +
    'a printed reference and USB media; add a service plan for a year of updates and expert support.</p>' +
    '<a href="/casi-spa/en/EUR/c/casi-all">Shop the Basic Ontology</a></div>', EN)
}
def homepage = flexibleSearchService.search("SELECT {pk} FROM {ContentPage} WHERE {uid}='homepage' AND {catalogVersion}=?cv", [cv: cv]).result[0]
def bannerTypes = ['SimpleResponsiveBannerComponent', 'BannerComponent', 'SimpleBannerComponent', 'RotatingImagesComponent', 'SimpleResponsiveBannerComponent']
def pageSlots = flexibleSearchService.search("SELECT {pk} FROM {ContentSlotForPage} WHERE {page}=?p", [p: homepage]).result.sort(false) { it.position }
pageSlots.each { b ->
  def slot = b.contentSlot
  def kept = slot.cmsComponents.findAll { !(it.itemtype in bannerTypes) && it.uid != 'CasiHeroParagraph' }
  if (b.position == 'Section1') kept = [hero] + kept
  if (kept != slot.cmsComponents) { slot.cmsComponents = kept; modelService.save(slot); out << 'homepage slot ' + b.position + ' -> ' + kept*.uid }
}
if (!pageSlots.any { it.position == 'Section1' }) out << 'WARN: homepage has no Section1 page slot: ' + pageSlots*.position

// 3. product carousels show the CASI products ---------------------------------------------
def casiProducts = ['CASI-ONTOLOGY', 'CASI-ONTOLOGY-PLAN'].collect { productService.getProductForCode(productCv, it) }
inCv('ProductCarouselComponent').each { c -> c.products = casiProducts; c.categories = []; modelService.save(c) }
out << 'product carousels -> CASI products: ' + inCv('ProductCarouselComponent').size()

// 4. returns on the order details page ------------------------------------------------------
def returnsComp = upsert(CMSFlexComponentModel, 'CMSFlexComponent', 'CasiS4ReturnsComponent') { c -> c.name = 'CASI S/4 returns'; c.flexType = 'CasiS4ReturnsComponent' }
def orderPage = flexibleSearchService.search("SELECT {pk} FROM {ContentPage} WHERE {uid}='order' AND {catalogVersion}=?cv", [cv: cv]).result[0]
def body = flexibleSearchService.search("SELECT {pk} FROM {ContentSlotForPage} WHERE {page}=?p AND {position}='BodyContent'", [p: orderPage]).result[0]?.contentSlot
if (body) {
  def comps = body.cmsComponents.findAll { it.uid != returnsComp.uid }
  def at = comps.findIndexOf { it.uid == 'AccountOrderDetailsPDFInvoicesComponent' }
  comps.add(at >= 0 ? at + 1 : comps.size(), returnsComp)
  body.cmsComponents = comps; modelService.save(body)
  out << 'order page BodyContent -> ' + comps*.uid
} else {
  out << 'WARN: order page BodyContent slot not found'
}

println out.join('\n')
