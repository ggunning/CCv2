// CASI Shop — sign-in for the hosted composable storefront (2211-jdk21 authorization code flow + PKCE).
// Per SAP Help "Installing SAP Commerce Cloud 2211-jdk21 for use with Composable Storefront" and the
// CCv2 notes in SAP Community ("SAP Commerce Cloud with Composable Storefront and SmartEdit: Config
// Notes from a Demo Server Upgrade"):
//  - public OAuth client mobile_android_public (the storefront default): no secret, ROLE_CLIENT, scope
//    basic, authorization_code + refresh_token, redirect URIs = storefront hosts, custom login page
//    https://{redirectUriHost}/login
//  - authorization server CORS lists the storefront origins explicitly (credentials need explicit origins)
// The companion startup property authserver.oauthclientdetails.loginpageuri.allowed.hosts is set on the
// hcs_common service in Cloud Portal (it is read at startup). Re-runnable; HAC scripting, commit ON.

def HOSTS = ['https://casishop.cu2qdtboy0-presalesm1-d3-public.model-t.cc.commerce.ondemand.com']
def out = []

// The 221121 storefront asks for a site-specific client, mobile_android_public_<baseSite>, and sends the
// redirect URI with the site path (https://<host>/casi-spa). The plain client is kept as a fallback.
def SITE = 'casi-spa'
['mobile_android_public', 'mobile_android_public_' + SITE].each { id ->
  def client = flexibleSearchService.search("SELECT {pk} FROM {OAuthClientDetails} WHERE {clientId}=?id", [id: id]).result[0]
  if (!client) { client = modelService.create('OAuthClientDetails'); client.clientId = id }   // clientId is set on create only
  client.public = true
  client.authorities = ['ROLE_CLIENT'] as Set
  client.scope = ['basic'] as Set
  client.authorizedGrantTypes = ['authorization_code', 'refresh_token'] as Set
  def redirects = HOSTS.collectMany { [it, it + '/' + SITE, it + '/oauth-callback'] }
  client.registeredRedirectUri = ((client.registeredRedirectUri ?: []) + redirects) as Set
  client.loginPageUri = 'https://{redirectUriHost}/login'
  modelService.save(client)
  out << 'client ' + client.clientId + ' public=' + client.public + ' redirects=' + client.registeredRedirectUri
}

// Authorization server CORS: keep any origins already configured, add the storefront hosts.
def cors = { String key -> flexibleSearchService.search("SELECT {pk} FROM {CorsConfigurationProperty} WHERE {context}='authorizationserver' AND {key}=?k", [k: key]).result[0] }
def put = { String key, String value ->
  def p = cors(key)
  if (!p) { p = modelService.create('CorsConfigurationProperty'); p.context = 'authorizationserver'; p.key = key }   // keys set on create only
  p.value = value
  modelService.save(p)
}
def origins = ((cors('allowedOrigins')?.value ?: '').split(/\s+/).findAll { it } + HOSTS).unique()
put('allowedOrigins', origins.join(' '))
if (!cors('allowCredentials')) put('allowCredentials', 'true')
if (!cors('allowedMethods')) put('allowedMethods', 'GET HEAD OPTIONS PATCH PUT POST DELETE')
if (!cors('allowedHeaders')) put('allowedHeaders', 'origin content-type accept authorization')
out << 'authorizationserver CORS origins: ' + cors('allowedOrigins').value

println out.join('\n')
