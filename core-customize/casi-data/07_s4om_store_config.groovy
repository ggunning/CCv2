// CASI Shop — SAP Base Store Configuration for SAP S/4HANA Order Management (needs build 788f495+).
// Values follow SAP Help "Configuring Backoffice for SAP S/4HANA Order Management":
//  Common Settings: order type, reference customer, sales org / channel / division, shipping condition
//  S/4HANA Order Management: synchronous order creation + real-time order history ON; S/4 catalog/cart
//    pricing OFF (BE6's pricing procedure has no price condition, so Commerce prices are shown);
//    S/4 ATP + credit check OFF (BE6 has no stock for CASI products); document flow ON
//  Consumed destination mapping: the destinations from 06_s4_destinations.groovy
//  Base store: SAP configuration + "SAP Invoice Enabled"
// Re-runnable. Run in HAC scripting with commit ON.

def NAME = 'casiS4Configuration'
def EN = Locale.ENGLISH
def out = []

def cfg = flexibleSearchService.search("SELECT {pk} FROM {SAPConfiguration} WHERE {core_name}=?n", [n: NAME]).result[0] ?: modelService.create('SAPConfiguration')
cfg.core_name = NAME
// Common settings
cfg.sapcommon_transactionType = 'OR'
cfg.sapcommon_referenceCustomer = '1000002'
cfg.sapcommon_salesOrganization = 'DE10'
cfg.sapcommon_distributionChannel = '10'
cfg.sapcommon_division = '10'
// S/4HANA Order Management
cfg.saps4synchronousorderandhistoryenabled = true
cfg.saps4synchronousorderhistoryenabled = true
cfg.saps4omcatalogpricing = false
cfg.saps4omcartpricing = false
cfg.saps4omcacheprice = false
cfg.saps4omatpcheckactive = false
cfg.saps4omcreditcheckactive = false
cfg.sapS4OrderSubsequentDocumentsActive = true
cfg.sapS4OrderAttachmentsActive = false
// Partner functions as BE6's API reports them in English (sold-to SP, ship-to SH, bill-to BP)
cfg.setSoldToPartnerFunctionCode('SP', EN)
cfg.setShipToPartnerFunctionCode('SH', EN)
cfg.setBillToPartnerFunctionCode('BP', EN)
modelService.save(cfg)
out << 'SAPConfiguration ' + NAME + ' saved'

// Shipping: Commerce delivery mode -> S/4 shipping condition 01
def modes = ['standard-net', 'premium-net'].collect { code ->
  def dm = deliveryModeService.getDeliveryModeForCode(code)
  def m = flexibleSearchService.search("SELECT {pk} FROM {SAPDeliveryMode} WHERE {deliveryMode}=?d", [d: dm]).result.find { it.deliveryValue == '01' } ?: modelService.create('SAPDeliveryMode')
  m.deliveryMode = dm; m.deliveryValue = '01'
  modelService.save(m); m
}
cfg.sapDeliveryModes = modes
modelService.save(cfg)
out << 'delivery modes -> shipping condition 01: ' + modes*.deliveryMode*.code

// Consumed destination mapping
// destination id -> SAPDestinationKey (the role S/4OM looks the destination up by)
def ids = [s4omOrderSimulateDestination: 'S4OMORDERSIMULATEDESTINATION', s4omOrderDestination: 'S4OMORDERDESTINATION',
           billingDestination: 'BILLINGDESTINATION', billingPDFDestination: 'BILLINGPDFDESTINATION',
           s4omDocumentHeaderAPIDestination: 'S4OMORDERDOCUMENTHEADERDESTINATION',
           s4omDocumentItemAPIDestination: 'S4OMORDERDOCUMENTITEMDESTINATION']
def mapped = flexibleSearchService.search("SELECT {pk} FROM {SAPConsumedDestinationEntry} WHERE {sapConfiguration}=?c", [c: cfg]).result*.consumedDestination*.id
ids.each { id, key ->
  if (id in mapped) return
  def dest = flexibleSearchService.search("SELECT {pk} FROM {ConsumedDestination} WHERE {id}=?id", [id: id]).result[0]
  if (!dest) { out << 'WARN: destination missing: ' + id; return }
  def e = modelService.create('SAPConsumedDestinationEntry')
  e.consumedDestination = dest; e.sapConfiguration = cfg
  e.destinationKey = enumerationService.getEnumerationValue('SAPDestinationKey', key)
  modelService.save(e)
}
out << 'destination mapping: ' + flexibleSearchService.search("SELECT {pk} FROM {SAPConsumedDestinationEntry} WHERE {sapConfiguration}=?c", [c: cfg]).result*.consumedDestination*.id

// Base store
def store = baseStoreService.getBaseStoreForUid('casi')
store.SAPConfiguration = cfg
store.sapInvoiceEnabled = true
modelService.save(store)
out << 'store casi -> ' + store.SAPConfiguration.core_name + ', SAP invoices ' + store.sapInvoiceEnabled

println out.join('\n')
