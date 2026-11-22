// CASI Shop — consumed destinations to S/4HANA Cloud BE6 (OData V2), per SAP Help
// "Configuring Backoffice for SAP S/4HANA Order Management" (+ Consumed Destination, Billing Invoice,
// Subsequent Document Flow), plus casiCustomerReturnDestination for the casireturnsocc extension and the
// quotation, contract and delivery destinations for the documents of the casiserviceocc extension
// (the S/4 communication user must be allowed to call API_SALES_QUOTATION_SRV, API_SALES_CONTRACT_SRV and
// API_OUTBOUND_DELIVERY_SRV: each needs its communication arrangement in S/4).
// One Basic credential: username LC_API_USER (S/4 communication user). The PASSWORD IS NOT SET HERE:
// enter it in Backoffice > System > API > Credentials > casiS4hcBE6Credential.
// Re-runnable (finds by id). Run in HAC scripting with commit ON.

def HOST = 'https://my4102318-api.lab.s4hana.cloud.sap'
def META = '$metadata'
def out = []
def byId = { String type, String id -> flexibleSearchService.search("SELECT {pk} FROM {" + type + "} WHERE {id}=?id", [id: id]).result[0] }

def target = byId('DestinationTarget', 'casiS4hcBE6') ?: modelService.create('DestinationTarget')
target.id = 'casiS4hcBE6'
try { target.destinationChannel = enumerationService.getEnumerationValue('DestinationChannel', 'DEFAULT') } catch (ignored) { }
modelService.save(target)

// Commerce requires a password on a Basic credential, so it is created by a person in Backoffice
// (System > API > Credentials > Basic Credential: id casiS4hcBE6Credential, username LC_API_USER).
def cred = byId('BasicCredential', 'casiS4hcBE6Credential')
if (!cred) {
  println 'STOP: create Basic Credential casiS4hcBE6Credential (username LC_API_USER + its password) in Backoffice first.'
  return
}
out << 'credential casiS4hcBE6Credential (username ' + cred.username + ')'

// destination id, OData service, entity
def defs = [
  ['s4omOrderSimulateDestination', 'API_SALES_ORDER_SIMULATION_SRV', 'A_SalesOrderSimulation'],
  ['s4omOrderDestination', 'API_SALES_ORDER_SRV', 'A_SalesOrder'],
  ['billingDestination', 'API_BILLING_DOCUMENT_SRV', 'A_BillingDocumentItem'],
  ['billingPDFDestination', 'API_BILLING_DOCUMENT_SRV', 'GetPDF'],
  ['s4omDocumentHeaderAPIDestination', 'API_SALES_ORDER_SRV', 'A_SalesOrderSubsqntProcFlow'],
  ['s4omDocumentItemAPIDestination', 'API_SALES_ORDER_SRV', 'A_SalesOrderItmSubsqntProcFlow'],
  ['casiCustomerReturnDestination', 'API_CUSTOMER_RETURN_SRV', 'A_CustomerReturn'],
  ['casiSalesQuotationDestination', 'API_SALES_QUOTATION_SRV', 'A_SalesQuotation'],
  ['casiSalesContractDestination', 'API_SALES_CONTRACT_SRV', 'A_SalesContract'],
  ['casiOutboundDeliveryDestination', 'API_OUTBOUND_DELIVERY_SRV;v=0002', 'A_OutbDeliveryHeader'],
  ['casiBusinessPartnerDestination', 'API_BUSINESS_PARTNER', 'A_BusinessPartner'],
]
defs.each { id, service, entity ->
  def root = HOST + '/sap/opu/odata/sap/' + service + '/'
  def endpoint = flexibleSearchService.search("SELECT {pk} FROM {Endpoint} WHERE {id}=?id AND {version}='v1'", [id: id + 'Endpoint']).result[0]
  // id and version can only be set when the endpoint is created
  if (!endpoint) { endpoint = modelService.create('Endpoint'); endpoint.id = id + 'Endpoint'; endpoint.version = 'v1' }
  endpoint.name = service + ' (' + entity + ')'; endpoint.specUrl = root + META
  modelService.save(endpoint)
  def dest = flexibleSearchService.search("SELECT {pk} FROM {ConsumedDestination} WHERE {id}=?id AND {destinationTarget}=?t", [id: id, t: target]).result[0]
  // id and target can only be set when the destination is created
  if (!dest) { dest = modelService.create('ConsumedDestination'); dest.id = id; dest.destinationTarget = target }
  dest.url = root; dest.endpoint = endpoint; dest.credential = cred; dest.active = true
  dest.additionalProperties = [csrfURL: root + META, entity: entity]
  modelService.save(dest)
  out << id + ' -> ' + root + ' (entity ' + entity + ')'
}
println out.join('\n')
