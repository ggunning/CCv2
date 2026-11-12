// CASI Shop - B2B checkout smoke test run as the test buyer in HAC (no password needed), step by step,
// through the same facades the OCC B2B checkout uses. Each step prints OK or the exception chain.
// PLACE_ORDER = false: stops before the order (run with commit OFF = rolled back).
// PLACE_ORDER = true: places the order (S/4HANA Order Management creates it in S/4); run with commit ON.
// Note: CCv2's XSS filter strips anything between a less-than and a greater-than sign from HAC requests,
// so this script avoids those characters entirely.
import de.hybris.platform.servicelayer.session.SessionExecutionBody
import de.hybris.platform.b2bacceleratorfacades.order.data.B2BPaymentTypeData
import de.hybris.platform.b2bcommercefacades.company.data.B2BCostCenterData
import de.hybris.platform.commercefacades.order.data.CartData

def PLACE_ORDER = false
def ctx = de.hybris.platform.core.Registry.getApplicationContext()
def out = []
def chain = { Throwable t ->
  def parts = []
  while (t) { parts.add(t.class.simpleName + ': ' + t.message); t = t.cause }
  parts.join(' / caused by ')
}
def step = { String name, Closure body ->
  try {
    def r = body()
    out.add('OK   ' + name + (r == null ? '' : ' = ' + r))
    r
  } catch (Throwable t) {
    out.add('FAIL ' + name + ': ' + chain(t))
    null
  }
}

def buyer = userService.getUserForUID('casi.buyer@example.com')
def run = {
  userService.setCurrentUser(buyer)
  baseSiteService.setCurrentBaseSite(baseSiteService.getBaseSiteForUID('casi-spa'), true)
  commonI18NService.setCurrentCurrency(commonI18NService.getCurrency('EUR'))
  commonI18NService.setCurrentLanguage(commonI18NService.getLanguage('en'))
  catalogVersionService.setSessionCatalogVersions([catalogVersionService.getCatalogVersion('casiProductCatalog', 'Online')])

  def cartFacade = ctx.getBean('cartFacade')
  def b2b = ctx.getBean('b2bCheckoutFacade')
  step('new session cart') { cartService.removeSessionCart(); cartService.getSessionCart().code }
  step('add CASI-ONTOLOGY x1') { cartFacade.addToCart('CASI-ONTOLOGY', 1L).statusCode }
  step('payment type ACCOUNT and cost center') {
    def data = new CartData()
    def pt = new B2BPaymentTypeData(); pt.code = 'ACCOUNT'; data.paymentType = pt
    def cc = new B2BCostCenterData(); cc.code = 'CASI-CC-1000002'; data.costCenter = cc
    b2b.updateCheckoutCart(data)
    cartService.getSessionCart().paymentType?.code
  }
  step('delivery address') {
    def addr = buyer.defaultB2BUnit.addresses.find { it.shippingAddress }
    b2b.setDeliveryAddress(ctx.getBean('addressConverter').convert(addr))
    cartService.getSessionCart().deliveryAddress?.town
  }
  step('supported delivery modes') { b2b.supportedDeliveryModes*.code }
  step('delivery mode standard-net') { b2b.setDeliveryMode('standard-net'); cartService.getSessionCart().deliveryMode?.code }
  step('placeOrder signature') { b2b.class.methods.findAll { it.name == 'placeOrder' }.collect { it.parameterTypes*.name } }
  step('checkout cart (review data)') {
    def c = b2b.checkoutCart
    'total ' + c.totalPrice?.formattedValue + ', delivery ' + c.deliveryMode?.code + ', payment ' + c.paymentType?.code
  }
  if (PLACE_ORDER) {
    step('place order') {
      // the PlaceOrderData class is taken from the facade's own signature (its package differs by release)
      def podClass = b2b.class.methods.find { it.name == 'placeOrder' && it.parameterCount == 1 }.parameterTypes[0]
      def pod = podClass.getDeclaredConstructor().newInstance()
      pod.termsCheck = true
      def data = b2b.placeOrder(pod)
      'order ' + data.code + ' status ' + data.statusDisplay
    }
  }
  null
}
sessionService.executeInLocalView([execute: run] as SessionExecutionBody)
println out.join('\n')
