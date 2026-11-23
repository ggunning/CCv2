// CASI Shop — configurable bundle "Advanced Ontology" (configurablebundleservices).
// The Advanced Ontology is sold with exactly one of its service plans:
//   CASI_ADV_BUNDLE                 root
//     CASI_ADV_BUNDLE_ONTOLOGY      CASI-ONTOLOGY-ADV, pick exactly 1
//     CASI_ADV_BUNDLE_PLAN          Basic | Standard | Premium plan, pick exactly 1, needs the Ontology component
// Each product keeps its own price (01_catalog_products.impex = the S/4 price records): the order carries two
// ordinary entries in one entry group, so S/4 gets two lines and prices them as usual. No bundle price rule.
// The shop's plan picker (js-storefront/casishop, casi/bundle-picker) lists the plans from product references
// of type SELECT on the Advanced Ontology: OCC neither returns a product's bundle templates nor the products
// allowed in a component (allowedProductsSearch needs the search index to know the templates).
// Staged and Online are written alike. Re-runnable. Run with commit ON, after 01.
import de.hybris.platform.configurablebundleservices.enums.BundleTemplateStatusEnum

def CATALOG = 'casiProductCatalog'
def VERSION = '1.0'
def ROOT = 'CASI_ADV_BUNDLE'
def COMPONENTS = [   // id, name, products, ids of the components it needs
  [id: 'CASI_ADV_BUNDLE_ONTOLOGY', name: 'Ontology', products: ['CASI-ONTOLOGY-ADV'], needs: []],
  [id: 'CASI_ADV_BUNDLE_PLAN', name: 'Service plan', products: ['CASI-ADV-PLAN-BAS', 'CASI-ADV-PLAN-STD', 'CASI-ADV-PLAN-PRM'], needs: ['CASI_ADV_BUNDLE_ONTOLOGY']],
]

def find = { String type, String id, cv ->
  def r = flexibleSearchService.search("SELECT {pk} FROM {" + type + "} WHERE {id}=?id AND {catalogVersion}=?cv", [id: id, cv: cv]).result
  r ? r[0] : null
}

['Staged', 'Online'].each { ver ->
  def cv = catalogVersionService.getCatalogVersion(CATALOG, ver)
  def root = find('BundleTemplate', ROOT, cv)
  if (!root) {
    root = modelService.create('BundleTemplate')
    root.id = ROOT; root.version = VERSION; root.catalogVersion = cv
  }
  root.setName('Advanced Ontology with service plan', Locale.ENGLISH)
  root.setDescription('The Advanced Ontology with one service plan: Basic, Standard or Premium.', Locale.ENGLISH)
  modelService.save(root)

  def made = [:]
  COMPONENTS.each { c ->
    def t = find('BundleTemplate', c.id, cv)
    if (!t) {
      t = modelService.create('BundleTemplate')
      t.id = c.id; t.version = VERSION; t.catalogVersion = cv; t.parentTemplate = root
    }
    t.setName(c.name, Locale.ENGLISH)
    t.products = c.products.collect { productService.getProductForCode(cv, it) }
    def pick = find('BundleSelectionCriteria', c.id + '_PICK1', cv)
    if (!pick) {
      pick = modelService.create('PickExactlyNBundleSelectionCriteria')
      pick.id = c.id + '_PICK1'; pick.catalogVersion = cv
    }
    pick.n = 1
    modelService.save(pick)
    t.bundleSelectionCriteria = pick
    t.requiredBundleTemplates = c.needs.collect { made[it] }
    modelService.save(t)
    made[c.id] = t
  }

  // plans as SELECT references of the bundle's main product, in the order of the plan component
  def main = productService.getProductForCode(cv, COMPONENTS[0].products[0])
  def refs = (main.productReferences ?: []) as List
  def wanted = COMPONENTS[1].products.collect { code ->
    def qual = 'casi-adv-plan-' + code
    def ref = refs.find { it.qualifier == qual }
    if (!ref) {
      ref = modelService.create('ProductReference')
      ref.qualifier = qual; ref.source = main
    }
    ref.target = productService.getProductForCode(cv, code)
    ref.referenceType = de.hybris.platform.catalog.enums.ProductReferenceTypeEnum.SELECT
    ref.quantity = 1; ref.active = true; ref.preselected = false
    modelService.save(ref)
    ref
  }
  main.productReferences = refs.findAll { r -> !wanted.any { it.pk == r.pk } } + wanted
  modelService.save(main)

  modelService.refresh(root)
  if (root.status.status != BundleTemplateStatusEnum.APPROVED) {
    root.status.status = BundleTemplateStatusEnum.APPROVED
    modelService.save(root.status)
  }
  println ver + ': ' + root.id + ' ' + root.status.status + ', ' + root.childTemplates.collect { it.id + ' ' + it.products*.code }.join(', ') + ', plan references ' + wanted.size()
}
'done'
