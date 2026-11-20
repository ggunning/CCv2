// Sodastream: sign-in and SmartEdit for every Spartacus storefront host of THIS environment.
// Works unchanged on d1, d2 and d3: hosts come from the environment's own ccv2.services.jsapps.url.N
// (one per JS storefront endpoint in Cloud Portal), nothing is hard-coded to a domain.
//
// What it sets (database, per environment):
//  1. OAuth client mobile_android_public_<site> for every site in SITES (Spartacus 221121 asks for a
//     site-specific client), plus the plain mobile_android_public fallback: public, ROLE_CLIENT, scope
//     basic, authorization_code + refresh_token. Redirect URIs for every storefront host:
//     https://host, https://host/<site>, https://host/<site>/oauth-callback, https://host/oauth-callback
//     (Spartacus redirects to <page origin>/<site>). Existing redirect URIs are kept.
//  2. Authorization server CORS (CorsConfigurationProperty, context authorizationserver):
//     allowedOriginPatterns = https://*.<this environment's domain> (+ localhost), credentials allowed.
//  3. SmartEdit whiteListedStorefronts: the storefront hosts are added.
//  4. CMSSite.previewURL for sites in PREVIEW_HOSTS: the existing path on that storefront host, ending in
//     /cx-preview (the dynamic apps' storefrontPreviewRoute).
// Login page hosts: authserver.oauthclientdetails.loginpageuri.allowed.hosts is set in manifest.json from
// ${ccv2.services.jsapps.url.0..9}, so CCv2 fills in this environment's storefront hosts at startup.
// The script only CHECKS it; a mismatch usually means a Cloud Portal service property overrides it.
//
// Re-runnable. Run in HAC scripting with commit OFF first (rolls back), then commit ON.

// ----------------------------------------------------------------------------------------- settings
// Sites that need sign-in. Empty list = every active CMSSite whose uid ends in "-spa".
def SITES = []
// Site -> first label of the storefront host that should open it in SmartEdit (e.g. 'casishop' for
// https://casishop.<env domain>). Entries whose host is not a storefront endpoint here are skipped.
def PREVIEW_HOSTS = ['casi-spa': 'casishop']
// Extra origins, e.g. local development.
def EXTRA_HOSTS = ['http://localhost:4200', 'https://localhost:4200']
// ---------------------------------------------------------------------------------------------------

def cfg = de.hybris.platform.util.Config
def out = []

def jsHosts = (0..49).collect { cfg.getParameter('ccv2.services.jsapps.url.' + it) }
        .findAll { it }.collect { it.trim().replaceAll('/+$', '') }.unique()
if (!jsHosts) {
  println 'No ccv2.services.jsapps.url.N found: this environment has no JS storefront endpoints. Nothing changed.'
  return
}
def HOSTS = (jsHosts + EXTRA_HOSTS).unique()
out << 'storefront hosts (this environment): ' + jsHosts.join(', ')

def sites = SITES ?: flexibleSearchService.search(
        "SELECT {pk} FROM {CMSSite} WHERE {active}=1 AND {uid} LIKE '%-spa'").result*.uid.sort()
out << 'sites: ' + sites.join(', ')

// 1. OAuth clients -------------------------------------------------------------------------------
def findClient = { String id ->
  flexibleSearchService.search("SELECT {pk} FROM {OAuthClientDetails} WHERE {clientId}=?id", [id: id]).result[0]
}
def upsertClient = { String id, List<String> redirects ->
  def client = findClient(id)
  def created = !client
  if (created) { client = modelService.create('OAuthClientDetails'); client.clientId = id }   // set on create only
  client.public = true
  client.authorities = ['ROLE_CLIENT'] as Set
  client.scope = ['basic'] as Set
  client.authorizedGrantTypes = ['authorization_code', 'refresh_token'] as Set
  def before = (client.registeredRedirectUri ?: []) as Set
  client.registeredRedirectUri = (before + redirects) as Set
  if (!client.loginPageUri) client.loginPageUri = 'https://{redirectUriHost}/login'
  modelService.save(client)
  out << 'client ' + id + (created ? ' (new)' : '') + ': +' + (client.registeredRedirectUri - before).size() + ' redirect URIs'
}
sites.each { site ->
  upsertClient('mobile_android_public_' + site,
          HOSTS.collectMany { [it, it + '/' + site, it + '/' + site + '/oauth-callback', it + '/oauth-callback'] })
}
upsertClient('mobile_android_public', HOSTS.collectMany { [it, it + '/oauth-callback'] })

// 2. Authorization server CORS -------------------------------------------------------------------
def cors = { String key ->
  flexibleSearchService.search("SELECT {pk} FROM {CorsConfigurationProperty} WHERE {context}='authorizationserver' AND {key}=?k", [k: key]).result[0]
}
def putCors = { String key, String value ->
  def p = cors(key)
  if (!p) { p = modelService.create('CorsConfigurationProperty'); p.context = 'authorizationserver'; p.key = key }   // set on create only
  p.value = value
  modelService.save(p)
}
// One wildcard pattern for this environment's domain (the value column is too short for a host list):
// https://*.<subscription>-<env>-public.model-t.cc.commerce.ondemand.com covers every storefront
// endpoint here, including ones added later. Existing explicit allowedOrigins are left as they are.
def apiUrl = cfg.getParameter('ccv2.services.api.url.0') ?: jsHosts[0]
def envDomain = apiUrl.replaceFirst('^https?://', '').split('/')[0].replaceFirst('^[^.]+\\.', '')
def patterns = (['https://*.' + envDomain] + EXTRA_HOSTS).unique().join(' ')
putCors('allowedOriginPatterns', patterns)
if (!cors('allowCredentials')) putCors('allowCredentials', 'true')
if (!cors('allowedMethods')) putCors('allowedMethods', 'GET HEAD OPTIONS PATCH PUT POST DELETE')
if (!cors('allowedHeaders')) putCors('allowedHeaders', 'origin content-type accept authorization')
out << 'authorizationserver CORS allowedOriginPatterns: ' + patterns

// 3. SmartEdit allowed storefronts ---------------------------------------------------------------
def seCfg = flexibleSearchService.search("SELECT {pk} FROM {SmartEditConfiguration} WHERE {key}='whiteListedStorefronts'").result[0]
if (!seCfg) { seCfg = modelService.create('SmartEditConfiguration'); seCfg.key = 'whiteListedStorefronts'; seCfg.value = '[]' }
def listed = []
try { listed = new groovy.json.JsonSlurper().parseText(seCfg.value ?: '[]') as List } catch (e) { out << 'whiteListedStorefronts was not JSON, replaced: ' + seCfg.value }
def allowed = (listed + HOSTS).unique()
seCfg.value = groovy.json.JsonOutput.toJson(allowed)
modelService.save(seCfg)
out << 'SmartEdit whiteListedStorefronts: ' + allowed.join(', ')

// 4. Preview URLs --------------------------------------------------------------------------------
PREVIEW_HOSTS.each { siteUid, label ->
  def host = jsHosts.find { it.replaceFirst('^https?://', '').split(/\./)[0] == label }
  def site = flexibleSearchService.search("SELECT {pk} FROM {CMSSite} WHERE {uid}=?u", [u: siteUid]).result[0]
  if (!site) { out << 'preview ' + siteUid + ': site not found, skipped'; return }
  if (!host) { out << 'preview ' + siteUid + ': no storefront endpoint "' + label + '" here, skipped'; return }
  def path = (site.previewURL ?: '/' + siteUid).replaceFirst('^https?://[^/]+', '')
  if (!path.startsWith('/')) path = '/' + path
  path = path.replaceAll('/+$', '').replaceAll('/cx-preview$', '') + '/cx-preview'
  def before = site.previewURL
  site.previewURL = host + path
  modelService.save(site)
  out << 'preview ' + siteUid + ': ' + before + ' -> ' + site.previewURL
}

// Startup property (Cloud Portal) -----------------------------------------------------------------
// Same parsing as the authorization server: comma list, each entry reduced to its host.
def hostOf = { String u -> try { new URI(u).host ?: u } catch (e) { u } }
def current = (cfg.getParameter('authserver.oauthclientdetails.loginpageuri.allowed.hosts') ?: '')
        .split(',')*.trim().findAll { it }.collect { hostOf(it) }
def missing = jsHosts.collect { hostOf(it) }.findAll { !current.contains(it) }
out << (missing ? 'CHECK: login page hosts missing ' + missing.join(', ') +
        ' - the manifest value is ${ccv2.services.jsapps.url.0..9}; remove any Cloud Portal service property' +
        ' that overrides authserver.oauthclientdetails.loginpageuri.allowed.hosts, then restart.'
        : 'login page hosts: every storefront host is allowed')

println out.join('\n')
