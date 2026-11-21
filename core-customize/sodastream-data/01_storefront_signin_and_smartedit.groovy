// Sodastream: sign-in and SmartEdit for every Spartacus storefront host of THIS environment.
// Works unchanged on d1, d2 and d3: hosts come from the environment's own ccv2.services.jsapps.url.N
// (one per JS storefront endpoint in Cloud Portal) plus https://<app>.<this environment's domain> for
// every app in APP_HOSTS (each of those hosts still needs its own JS storefront endpoint in Cloud Portal
// to answer; listing it here only registers it for sign-in in advance).
// Nothing is hard-coded to a domain.
//
// What it sets (database, per environment):
//  1. OAuth client mobile_android_public_<site> for every site in SITES (Spartacus 221121 asks for a
//     site-specific client), plus the plain mobile_android_public fallback: public, ROLE_CLIENT, scope
//     basic, authorization_code + refresh_token. Redirect URIs for every storefront host:
//     https://host, https://host/<site>, https://host/<site>/oauth-callback, https://host/oauth-callback
//     (the dynamic apps always use mobile_android_public_<site> and <page origin>/<site>).
//     Existing redirect URIs are kept. An empty loginPageUri (= the authorization server's own login
//     form, works on any host) is only set to Spartacus' login page when every storefront host is an
//     allowed login page host. An existing loginPageUri is left as it is.
//  2. Authorization server CORS (CorsConfigurationProperty, context authorizationserver):
//     allowedOriginPatterns = https://*.<this environment's domain> (+ localhost), credentials allowed,
//     allowedOrigins = localhost only when it is missing or "*" ("*" with credentials = HTTP 500 on sign-in).
//  3. SmartEdit whiteListedStorefronts: the storefront hosts are added.
//  4. CMSSite.previewURL (where SmartEdit opens the site): for sites in PREVIEW_HOSTS on that storefront
//     host; every other site whose preview URL is empty, relative or on localhost goes to dynamicb2bstore
//     (B2B channel) or dynamicb2cstore. The existing path is kept and ends in /cx-preview (the dynamic
//     apps' storefrontPreviewRoute). Preview URLs on any other host are left as they are.
//  5. OAuth client smartedit, which SmartEdit (2211) signs in with: public, authorization_code with PKCE +
//     refresh_token, scope basic + permissionswebservices, redirect <backoffice host>/smartedit. Without it
//     the SmartEdit login fails. (smartedit.sso.enabled=true only adds a "Sign in with SSO" button, which
//     needs SAML single sign-on; the normal sign-in works with this client.)
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
// JS storefront apps (js-storefront/manifest.json) whose host https://<app>.<env domain> gets sign-in.
// The host only answers once Cloud Portal has a JS storefront endpoint for it (no endpoint = TLS error).
def APP_HOSTS = ['dynamicb2cstore', 'dynamicb2bstore', 'spartacusstore', 'b2bspastore', 'apparelstore', 'casishop', 'pulsestore']
// SmartEdit hosts besides this environment's backoffice (ccv2.services.backoffice.url.0).
def SMARTEDIT_EXTRA_HOSTS = ['https://localhost:9002']
// Extra origins, e.g. local development.
def EXTRA_HOSTS = ['http://localhost:4200', 'https://localhost:4200']
// ---------------------------------------------------------------------------------------------------

def cfg = de.hybris.platform.util.Config
def out = []

def endpointHosts = (0..49).collect { cfg.getParameter('ccv2.services.jsapps.url.' + it) }
        .findAll { it }.collect { it.trim().replaceAll('/+$', '') }.unique()
// This environment's domain, e.g. <subscription>-d1-public.model-t.cc.commerce.ondemand.com
def apiUrl = cfg.getParameter('ccv2.services.api.url.0') ?: endpointHosts[0]
if (!apiUrl) {
  println 'Neither ccv2.services.api.url.0 nor ccv2.services.jsapps.url.N is set: not a CCv2 environment? Nothing changed.'
  return
}
def envDomain = apiUrl.replaceFirst('^https?://', '').split('/')[0].replaceFirst('^[^.]+\\.', '')
def jsHosts = (endpointHosts + APP_HOSTS.collect { 'https://' + it + '.' + envDomain }).unique()
def HOSTS = (jsHosts + EXTRA_HOSTS).unique()
out << 'storefront hosts (this environment): ' + jsHosts.join(', ')

def sites = SITES ?: flexibleSearchService.search(
        "SELECT {pk} FROM {CMSSite} WHERE {active}=1 AND {uid} LIKE '%-spa'").result*.uid.sort()
out << 'sites: ' + sites.join(', ')

// Login page hosts allowed at startup (same parsing as the authorization server: comma list, each
// entry reduced to its host). Spartacus' own login page can only be used when all hosts are listed.
def hostOf = { String u -> try { new URI(u).host ?: u } catch (e) { u } }
def allowedLoginHosts = (cfg.getParameter('authserver.oauthclientdetails.loginpageuri.allowed.hosts') ?: '')
        .split(',')*.trim().findAll { it }.collect { hostOf(it) }
def missingLoginHosts = jsHosts.collect { hostOf(it) }.findAll { !allowedLoginHosts.contains(it) }

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
  if (!client.loginPageUri && !missingLoginHosts) client.loginPageUri = 'https://{redirectUriHost}/login'
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
// endpoint here, including ones added later.
def patterns = (['https://*.' + envDomain] + EXTRA_HOSTS).unique().join(' ')
putCors('allowedOriginPatterns', patterns)
// allowedOrigins must never be "*" here: the platform default corsfilter.authorizationserver.allowedOrigins=*
// together with allowCredentials=true makes Spring reject every request that has an Origin header, so
// /authorizationserver/oauth/token answers HTTP 500 on every storefront. A database row overrides the
// property, so write an explicit list when it is missing or "*" (the patterns above cover the storefronts).
// Any other explicit value is left as it is.
def origins = cors('allowedOrigins')?.value?.trim()
if (!origins || origins.split(/[\s,]+/).contains('*')) {
  putCors('allowedOrigins', EXTRA_HOSTS.join(' '))
  out << 'authorizationserver CORS allowedOrigins: ' + EXTRA_HOSTS.join(' ') + ' (was ' + (origins ?: 'the property default *') + ')'
}
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
def previewTargets = [:] + PREVIEW_HOSTS
sites.each { uid ->
  if (previewTargets.containsKey(uid)) return
  def s = flexibleSearchService.search("SELECT {pk} FROM {CMSSite} WHERE {uid}=?u", [u: uid]).result[0]
  def url = s?.previewURL ?: ''
  if (!url || !(url ==~ /(?i)^https?:.*/) || url ==~ /(?i)^https?:\/\/localhost([:\/].*)?$/) {
    previewTargets[uid] = (s?.channel?.code == 'B2B') ? 'dynamicb2bstore' : 'dynamicb2cstore'
  }
}
previewTargets.each { siteUid, label ->
  def host = jsHosts.find { it.replaceFirst('^https?://', '').split(/\./)[0] == label }
  def site = flexibleSearchService.search("SELECT {pk} FROM {CMSSite} WHERE {uid}=?u", [u: siteUid]).result[0]
  if (!site) { out << 'preview ' + siteUid + ': site not found, skipped'; return }
  if (!host) { out << 'preview ' + siteUid + ': no storefront host "' + label + '" here, skipped'; return }
  def path = (site.previewURL ?: '/' + siteUid).replaceFirst('^https?://[^/]+', '')
  if (!path.startsWith('/')) path = '/' + path
  path = path.replaceAll('/+$', '').replaceAll('/cx-preview$', '') + '/cx-preview'
  def before = site.previewURL
  site.previewURL = host + path
  modelService.save(site)
  out << 'preview ' + siteUid + ': ' + before + ' -> ' + site.previewURL
}

// 5. SmartEdit OAuth client ------------------------------------------------------------------------
def boHosts = ((0..9).collect { cfg.getParameter('ccv2.services.backoffice.url.' + it) }.findAll { it }
        .collect { it.trim().replaceAll('/+$', '') } + SMARTEDIT_EXTRA_HOSTS).unique()
def se = findClient('smartedit')
def seCreated = !se
if (seCreated) { se = modelService.create('OAuthClientDetails'); se.clientId = 'smartedit' }   // set on create only
se.public = true
se.authorities = ['ROLE_CLIENT'] as Set
se.scope = ((se.scope ?: []) + ['basic', 'permissionswebservices']) as Set
se.authorizedGrantTypes = ((se.authorizedGrantTypes ?: []) + ['authorization_code', 'refresh_token']) as Set
def seBefore = (se.registeredRedirectUri ?: []) as Set
se.registeredRedirectUri = (seBefore + boHosts.collect { it + '/smartedit' }) as Set
modelService.save(se)
out << 'client smartedit' + (seCreated ? ' (new)' : '') + ': redirect URIs ' + se.registeredRedirectUri.sort().join(', ')

// Startup property -------------------------------------------------------------------------------
out << (missingLoginHosts ? 'NOTE: login page hosts not allowed: ' + missingLoginHosts.join(', ') +
        ' - sign-in uses the authorization server login form. For Spartacus login page add JS endpoints' +
        ' for these apps (the manifest lists ${ccv2.services.jsapps.url.0..9}) and remove any Cloud Portal' +
        ' service property overriding authserver.oauthclientdetails.loginpageuri.allowed.hosts, then restart.'
        : 'login page hosts: every storefront host is allowed')

println out.join('\n')
