// CASI Shop — My Company (self-service administration of the customer's own company).
// Run in HAC > Console > Scripting with commit ON (or rolled back to see what it would do). Re-runnable.
// Run after 02 (content clone).
//
// The shop's My Account menu has "My Company" (link MyCompanyLink -> /organization), and a company's first
// person is its administrator (casiserviceocc), but the pages behind it are not there: the sample content
// the CASI catalog was cloned from (powertools-spa) has neither the page template "CompanyPageTemplate" nor
// the pages on these environments, so the link ended on "page not found". This creates them in the CASI
// content catalog (Online; 02 clones into Online only):
//   /organization                  tiles to the pages below
//   /organization/units            company and its units, addresses, cost centers, users per unit
//   /organization/users            users of the company and their roles
//   /organization/cost-centers     cost centers
//   /organization/budgets          budgets
//   /organization/purchase-limits  purchase limits (permissions)
//   /organization/user-groups      user groups
// Account summary is left out: it needs the accountsummary add-on's data, which this shop does not have.
// The storefront shows the pages to company administrators only (Spartacus' AdminGuard on each component).
//
// Only the CASI content catalog is touched. The template takes over the header and footer slots of
// AccountPageTemplate, so the pages look like the other My Account pages.
import de.hybris.platform.cms2.enums.CmsApprovalStatus
import de.hybris.platform.cms2.model.contents.components.CMSFlexComponentModel
import de.hybris.platform.cms2.model.contents.contentslot.ContentSlotModel
import de.hybris.platform.cms2.model.pages.ContentPageModel
import de.hybris.platform.cms2.model.pages.PageTemplateModel
import de.hybris.platform.cms2.model.relations.ContentSlotForPageModel
import de.hybris.platform.cms2.model.relations.ContentSlotForTemplateModel
import de.hybris.platform.cms2lib.model.components.BannerComponentModel

def CONTENT_CATALOG = 'casiContentCatalog'
def TEMPLATE = 'CompanyPageTemplate'
def LIKE_TEMPLATE = 'AccountPageTemplate'
def EN = Locale.ENGLISH
// page uid, label, title, flex component of the page, tile (uid, headline, text)
def PAGES = [
  ['ManageUnits', '/organization/units', 'Units', 'ManageUnitsListComponent', 'UnitsHomeLink', 'Units', 'Your company and its units, with their addresses, cost centers and users.'],
  ['ManageUsers', '/organization/users', 'Users', 'ManageUsersListComponent', 'UsersHomeLink', 'Users', 'The people who can buy for your company, and what each of them may do.'],
  ['ManageCostCenters', '/organization/cost-centers', 'Cost centers', 'ManageCostCentersListComponent', 'CostCentersHomeLink', 'Cost centers', 'The cost centers orders are booked on.'],
  ['ManageBudgets', '/organization/budgets', 'Budgets', 'ManageBudgetsListComponent', 'BudgetsHomeLink', 'Budgets', 'Budgets for your cost centers.'],
  ['ManagePermissions', '/organization/purchase-limits', 'Purchase limits', 'ManagePermissionsListComponent', 'PurchaseLimitsHomeLink', 'Purchase limits', 'How much a user may order before an approval is needed.'],
  ['ManageUserGroups', '/organization/user-groups', 'User groups', 'ManageUserGroupsListComponent', 'UserGroupsHomeLink', 'User groups', 'Groups of users that share purchase limits.'],
]

def cv = catalogVersionService.getCatalogVersion(CONTENT_CATALOG, 'Online')
def find = { String type, String uid ->
  flexibleSearchService.search("SELECT {pk} FROM {" + type + "} WHERE {uid}=?u AND {catalogVersion}=?cv", [u: uid, cv: cv]).result[0]
}
// uid and catalogVersion are set on create only
def upsert = { Class cls, String type, String uid, Closure fill ->
  def item = find(type, uid)
  if (!item) { item = modelService.create(cls); item.uid = uid; item.catalogVersion = cv }
  fill(item); modelService.save(item); item
}
def made = []

// 1. page template, with the header and footer slots of the account pages
def like = find('PageTemplate', LIKE_TEMPLATE)
if (!like) { println "STOP: no page template ${LIKE_TEMPLATE} in ${CONTENT_CATALOG}: run 02 first"; return }
def template = upsert(PageTemplateModel, 'PageTemplate', TEMPLATE) { t ->
  t.name = 'Company Page Template'; t.active = true; t.restrictedPageTypes = like.restrictedPageTypes
}
def likeSlots = flexibleSearchService.search("SELECT {pk} FROM {ContentSlotForTemplate} WHERE {pageTemplate}=?t AND {catalogVersion}=?cv", [t: like, cv: cv]).result
def haveSlots = flexibleSearchService.search("SELECT {pk} FROM {ContentSlotForTemplate} WHERE {pageTemplate}=?t AND {catalogVersion}=?cv", [t: template, cv: cv]).result*.position
likeSlots.findAll { !(it.position in haveSlots) && it.position != 'BodyContent' }.each { b ->
  upsert(ContentSlotForTemplateModel, 'ContentSlotForTemplate', b.position + 'Slot-CompanyPage') { n ->
    n.position = b.position; n.pageTemplate = template; n.contentSlot = b.contentSlot; n.allowOverwrite = b.allowOverwrite
  }
  made << b.position
}
println 'template ' + TEMPLATE + ': slots taken from ' + LIKE_TEMPLATE + ' ' + (made ?: 'none new')

// 2. a page with one slot (BodyContent) and its components
def page = { String uid, String label, String title, List components ->
  def p = upsert(ContentPageModel, 'ContentPage', uid) { n ->
    n.name = title + ' (My Company)'; n.masterTemplate = template; n.label = label; n.defaultPage = true
    n.approvalStatus = CmsApprovalStatus.APPROVED; n.homepage = false; n.setTitle(title, EN)
  }
  def slot = upsert(ContentSlotModel, 'ContentSlot', 'BodyContentSlot-' + uid) { n -> n.name = title + ' slot'; n.active = true; n.cmsComponents = components }
  upsert(ContentSlotForPageModel, 'ContentSlotForPage', 'BodyContent-' + uid) { n -> n.position = 'BodyContent'; n.page = p; n.contentSlot = slot }
  println 'page ' + label + ' -> ' + components*.uid
}

def tiles = []
PAGES.each { uid, label, title, flex, tileUid, headline, text ->
  def comp = upsert(CMSFlexComponentModel, 'CMSFlexComponent', flex) { c -> c.name = title + ' list'; c.flexType = flex; c.visible = true }
  page(uid, label, title, [comp])
  tiles << upsert(BannerComponentModel, 'BannerComponent', tileUid) { b ->
    b.name = title + ' tile'; b.urlLink = label; b.visible = true; b.setHeadline(headline, EN); b.setContent(text, EN)
  }
}
page('MyCompany', '/organization', 'My Company', tiles)
println 'OK'
