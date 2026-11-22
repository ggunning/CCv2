// CASI Shop — consumed destination to SAP Sales and Service Cloud Version 2 for the service tickets
// (extension casiserviceocc: My Account > Customer Service lists, opens and answers cases there).
// One Basic credential casiSscCredential: an API user of the Sales and Service Cloud tenant. THE
// PASSWORD IS NOT SET HERE: LandscapeCommander `python main.py casi credential --env <env>` copies it
// from .env (C360SSC_API_USER / C360SSC_API_PASS), or enter it in Backoffice > System > API > Credentials.
// Re-runnable (finds by id). Run in HAC scripting with commit ON.

// The tenant, e.g. https://<tenant>.crm.cloud.sap — LandscapeCommander fills it in from C360SSC_BASE_URL.
def HOST = ''
// Employee display ID of the processor of new cases (a case cannot be saved without one), and the case
// types customers can choose. Both override casiserviceocc's project.properties for this environment.
def PROCESSOR = 'ADMINISTRATOR'
def CASE_TYPES = 'ZSMP,ZINQ,ZDSR,ZPAA'
// Company registration (POST /orgregistrations): the Sales and Service Cloud communication system that stands
// for S/4 (new accounts get the S/4 number as external ID in it). LandscapeCommander fills it in from the bridge settings.
def S4_SYSTEM_ID = ''

def out = []
def byId = { String type, String id -> flexibleSearchService.search("SELECT {pk} FROM {" + type + "} WHERE {id}=?id", [id: id]).result[0] }

if (!HOST) {
  println 'STOP: no Sales and Service Cloud host: set C360SSC_BASE_URL in .env (or HOST in this script).'
  return
}
def cred = byId('BasicCredential', 'casiSscCredential')
if (!cred) {
  println 'STOP: no Basic Credential casiSscCredential yet: set C360SSC_API_USER / C360SSC_API_PASS in .env and run `python main.py casi credential --env <env>`.'
  return
}
out << 'credential casiSscCredential (username ' + cred.username + ')'

def target = byId('DestinationTarget', 'casiSsc') ?: modelService.create('DestinationTarget')
target.id = 'casiSsc'
try { target.destinationChannel = enumerationService.getEnumerationValue('DestinationChannel', 'DEFAULT') } catch (ignored) { }
modelService.save(target)

def root = HOST.replaceAll('/+$', '') + '/sap/c4c/api/v1/'
def id = 'casiSscCaseDestination'
def endpoint = flexibleSearchService.search("SELECT {pk} FROM {Endpoint} WHERE {id}=?id AND {version}='v1'", [id: id + 'Endpoint']).result[0]
// id and version can only be set when the endpoint is created
if (!endpoint) { endpoint = modelService.create('Endpoint'); endpoint.id = id + 'Endpoint'; endpoint.version = 'v1' }
endpoint.name = 'Sales and Service Cloud REST API (cases)'; endpoint.specUrl = root
modelService.save(endpoint)
def dest = flexibleSearchService.search("SELECT {pk} FROM {ConsumedDestination} WHERE {id}=?id AND {destinationTarget}=?t", [id: id, t: target]).result[0]
// id and target can only be set when the destination is created
if (!dest) { dest = modelService.create('ConsumedDestination'); dest.id = id; dest.destinationTarget = target }
dest.url = root; dest.endpoint = endpoint; dest.credential = cred; dest.active = true
def props = [processor: PROCESSOR, caseTypes: CASE_TYPES]
if (S4_SYSTEM_ID) props.s4CommunicationSystemId = S4_SYSTEM_ID
dest.additionalProperties = props
modelService.save(dest)
out << id + ' -> ' + root + ' (processor ' + PROCESSOR + ', case types ' + CASE_TYPES + ', S/4 system ' + (S4_SYSTEM_ID ? 'set' : 'not set') + ')'
println out.join('\n')
