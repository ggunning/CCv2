// CASI Shop — "Don't have an account? Register" on the sign-in page.
// Run in HAC > Console > Scripting with commit ON (or: rolled back to see what it would do). Re-runnable.
//
// The storefront has the feature toggle a11yB2BRegisterComponent on. With it Spartacus hides the two
// CMS components the B2B sample content uses for this (NoAccountParagraphComponent and
// OrganizationUserRegistrationLink) and expects ONE flex component of type
// "ReturningOrganizationUserRegisterComponent" instead (heading + Register button, route "register" =
// /login/register, which in the CASI Shop is the company registration form). The content cloned from
// powertools-spa does not have that component, so a visitor saw a sign-in form and no way to register.
//
// Only the CASI content catalog is touched: its own component, its own sign-in slot.

def CONTENT_CATALOG = 'casiContentCatalog'
def SLOT = 'LeftContentSlot-login'
def UID = 'ReturningOrganizationUserRegisterComponent'

['Staged', 'Online'].each { version ->
  def cv = catalogVersionService.getCatalogVersion(CONTENT_CATALOG, version)
  def one = { String type, String uid ->
    flexibleSearchService.search("SELECT {pk} FROM {" + type + "} WHERE {uid}=?u AND {catalogVersion}=?cv", [u: uid, cv: cv]).result[0]
  }
  def slot = one('ContentSlot', SLOT)
  // 02 clones the content into Online only, so Staged normally has no such slot.
  if (!slot) { println "${version}: no slot ${SLOT}, skipped" + (version == 'Online' ? ' — STOP: run 02 first' : ''); return }
  def comp = one('CMSFlexComponent', UID)
  def created = !comp
  if (created) {
    comp = modelService.create('CMSFlexComponent')
    comp.uid = UID; comp.catalogVersion = cv               // set on create only
  }
  comp.name = 'Sign-in page: register (CASI)'
  comp.flexType = UID
  comp.visible = true
  modelService.save(comp)
  def list = new ArrayList(slot.cmsComponents ?: [])
  def added = !list.contains(comp)
  if (added) { list.add(comp); slot.cmsComponents = list; modelService.save(slot) }
  println "${version}: component ${created ? 'created' : 'kept'}, slot ${added ? 'extended' : 'unchanged'}: " + slot.cmsComponents*.uid
}
println 'OK'
