package com.casi.returnsocc.controllers;

import com.casi.returnsocc.s4.S4ODataClient;
import com.casi.returnsocc.s4.S4ODataClient.S4Exception;
import com.fasterxml.jackson.databind.JsonNode;
import de.hybris.platform.b2b.model.B2BCustomerModel;
import de.hybris.platform.b2b.model.B2BUnitModel;
import de.hybris.platform.core.model.user.UserModel;
import de.hybris.platform.servicelayer.user.UserService;
import de.hybris.platform.util.Config;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Customer returns for orders that live in SAP S/4HANA (SAP S/4HANA Order Management, synchronous
 * orders), without CPI. The order code is the S/4 sales order number; the caller's default B2B unit
 * must be the order's sold-to party. Returns are S/4 customer returns (API_CUSTOMER_RETURN_SRV) whose
 * PurchaseOrderByCustomer is "CASI-&lt;order&gt;-R&lt;n&gt;", which is also how they are listed per order.
 *
 * GET  /occ/v2/{baseSiteId}/users/{userId}/s4returns?orderCode=...
 * POST /occ/v2/{baseSiteId}/users/{userId}/s4returns  {"orderCode":"...","entries":[{"product":"...","quantity":1}]}
 */
@Controller
@RequestMapping(value = "/{baseSiteId}/users/{userId}/s4returns")
public class S4ReturnsController
{
	private static final Pattern DOCUMENT_NUMBER = Pattern.compile("[0-9A-Za-z]{1,10}");
	private static final Pattern PRODUCT_CODE = Pattern.compile("[0-9A-Za-z_\\-./]{1,40}");
	private static final Pattern ODATA_DATE = Pattern.compile("/Date\\((-?\\d+)");

	private UserService userService;
	private S4ODataClient s4Client;

	@Secured({ "ROLE_CUSTOMERGROUP", "ROLE_TRUSTED_CLIENT", "ROLE_CUSTOMERMANAGERGROUP" })
	@GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	@ResponseBody
	public ResponseEntity<Map<String, Object>> getReturns(@PathVariable("baseSiteId") final String baseSiteId,
			@PathVariable("userId") final String userId, @RequestParam("orderCode") final String orderCode)
	{
		try
		{
			final String unit = currentUnit();
			final String order = documentNumber(orderCode);
			final String filter = "SoldToParty eq '" + unit + "' and startswith(PurchaseOrderByCustomer,'" + referencePrefix(order)
					+ "') eq true";
			final JsonNode rows = s4Client.get(returnDestination(),
					"A_CustomerReturn?$format=json&$expand=to_Item&$orderby=CustomerReturn%20desc&$filter=" + encode(filter));
			final List<Map<String, Object>> returns = new ArrayList<>();
			for (final JsonNode row : rows.path("results"))
			{
				returns.add(toReturn(row));
			}
			final Map<String, Object> body = new LinkedHashMap<>();
			body.put("orderCode", order);
			body.put("returns", returns);
			return ResponseEntity.ok(body);
		}
		catch (final S4Exception e)
		{
			return error(e);
		}
	}

	@Secured({ "ROLE_CUSTOMERGROUP", "ROLE_TRUSTED_CLIENT", "ROLE_CUSTOMERMANAGERGROUP" })
	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	@ResponseBody
	public ResponseEntity<Map<String, Object>> createReturn(@PathVariable("baseSiteId") final String baseSiteId,
			@PathVariable("userId") final String userId, @RequestBody final Map<String, Object> request)
	{
		try
		{
			final String unit = currentUnit();
			final String order = documentNumber(String.valueOf(request.get("orderCode")));
			final JsonNode salesOrder = s4Client.get(orderDestination(),
					"A_SalesOrder('" + order + "')?$format=json&$expand=to_Item");
			if (!unit.equals(salesOrder.path("SoldToParty").asText()))
			{
				throw new S4Exception(404, "Order " + order + " not found");
			}

			final Map<String, JsonNode> itemByProduct = new LinkedHashMap<>();
			for (final JsonNode item : salesOrder.path("to_Item").path("results"))
			{
				itemByProduct.putIfAbsent(item.path("Material").asText(), item);
			}

			final List<Map<String, Object>> items = new ArrayList<>();
			final Object entries = request.get("entries");
			if (!(entries instanceof List) || ((List<?>) entries).isEmpty())
			{
				throw new S4Exception(400, "Choose at least one product to return");
			}
			for (final Object entry : (List<?>) entries)
			{
				if (!(entry instanceof Map))
				{
					throw new S4Exception(400, "Each return line needs a product and a quantity");
				}
				final Map<?, ?> line = (Map<?, ?>) entry;
				final String product = String.valueOf(line.get("product"));
				final BigDecimal quantity = new BigDecimal(String.valueOf(line.get("quantity")));
				final JsonNode orderItem = itemByProduct.get(product);
				if (!PRODUCT_CODE.matcher(product).matches() || orderItem == null)
				{
					throw new S4Exception(400, "Product " + product + " is not on order " + order);
				}
				final BigDecimal ordered = new BigDecimal(orderItem.path("RequestedQuantity").asText("0"));
				if (quantity.signum() <= 0 || quantity.compareTo(ordered) > 0)
				{
					throw new S4Exception(400, "Return between 1 and " + ordered.stripTrailingZeros().toPlainString() + " of " + product);
				}
				final Map<String, Object> item = new LinkedHashMap<>();
				item.put("CustomerReturnItem", String.valueOf((items.size() + 1) * 10));
				item.put("Material", product);
				item.put("RequestedQuantity", quantity.stripTrailingZeros().toPlainString());
				item.put("ReferenceSDDocument", order);
				item.put("ReferenceSDDocumentItem", orderItem.path("SalesOrderItem").asText());
				items.add(item);
			}

			final String prefix = referencePrefix(order);
			final JsonNode existing = s4Client.get(returnDestination(), "A_CustomerReturn?$format=json&$select=CustomerReturn&$filter="
					+ encode("startswith(PurchaseOrderByCustomer,'" + prefix + "') eq true"));
			final String reference = prefix + (existing.path("results").size() + 1);

			final Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("CustomerReturnType", Config.getString("casireturnsocc.return.type", "CBAR"));
			payload.put("SalesOrganization", salesOrder.path("SalesOrganization").asText());
			payload.put("DistributionChannel", salesOrder.path("DistributionChannel").asText());
			payload.put("OrganizationDivision", salesOrder.path("OrganizationDivision").asText());
			payload.put("SoldToParty", unit);
			payload.put("PurchaseOrderByCustomer", reference);
			final String reason = Config.getString("casireturnsocc.return.reason", "");
			if (!reason.isBlank())
			{
				payload.put("SDDocumentReason", reason);
			}
			payload.put("to_Item", items);

			boolean referenced = true;
			JsonNode created;
			try
			{
				created = s4Client.post(returnDestination(), "A_CustomerReturn", payload);
			}
			catch (final S4Exception e)
			{
				// S/4 refuses to reference a sales order that is incomplete (e.g. no shipping point
				// determined). Create the return without the reference rather than losing it.
				if (e.getMessage() == null || !e.getMessage().contains("cannot be referenced"))
				{
					throw e;
				}
				for (final Map<String, Object> item : items)
				{
					item.remove("ReferenceSDDocument");
					item.remove("ReferenceSDDocumentItem");
				}
				created = s4Client.post(returnDestination(), "A_CustomerReturn", payload);
				referenced = false;
			}

			final Map<String, Object> body = toReturn(created);
			body.put("orderCode", order);
			body.put("referencesOrder", referenced);
			return ResponseEntity.status(HttpStatus.CREATED).body(body);
		}
		catch (final NumberFormatException e)
		{
			return error(new S4Exception(400, "Quantity must be a number"));
		}
		catch (final S4Exception e)
		{
			return error(e);
		}
	}

	private String currentUnit()
	{
		final UserModel user = userService.getCurrentUser();
		if (!(user instanceof B2BCustomerModel))
		{
			throw new S4Exception(403, "Returns are available to business customers only");
		}
		final B2BUnitModel unit = ((B2BCustomerModel) user).getDefaultB2BUnit();
		if (unit == null)
		{
			throw new S4Exception(403, "Your account has no business unit");
		}
		return documentNumber(unit.getUid());
	}

	private static String documentNumber(final String value)
	{
		if (value == null || !DOCUMENT_NUMBER.matcher(value).matches())
		{
			throw new S4Exception(400, "Invalid document number");
		}
		return value;
	}

	private static String referencePrefix(final String order)
	{
		return "CASI-" + order + "-R";
	}

	private static Map<String, Object> toReturn(final JsonNode row)
	{
		final Map<String, Object> result = new LinkedHashMap<>();
		result.put("code", row.path("CustomerReturn").asText());
		result.put("reference", row.path("PurchaseOrderByCustomer").asText());
		result.put("created", isoDate(row.path("CreationDate").asText(null)));
		result.put("processStatus", row.path("OverallSDProcessStatus").asText(""));
		result.put("returnsStatus", row.path("RetsMgmtProcessingStatus").asText(""));
		final List<Map<String, Object>> entries = new ArrayList<>();
		for (final JsonNode item : row.path("to_Item").path("results"))
		{
			final Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("product", item.path("Material").asText());
			entry.put("description", item.path("CustomerReturnItemText").asText(""));
			entry.put("quantity", item.path("RequestedQuantity").asText());
			entries.add(entry);
		}
		result.put("entries", entries);
		return result;
	}

	private static String isoDate(final String odataDate)
	{
		if (odataDate == null)
		{
			return null;
		}
		final Matcher matcher = ODATA_DATE.matcher(odataDate);
		return matcher.find() ? Instant.ofEpochMilli(Long.parseLong(matcher.group(1))).toString() : odataDate;
	}

	private static String encode(final String value)
	{
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
	}

	private static String orderDestination()
	{
		return Config.getString("casireturnsocc.destination.order", "s4omOrderDestination");
	}

	private static String returnDestination()
	{
		return Config.getString("casireturnsocc.destination.return", "casiCustomerReturnDestination");
	}

	private static ResponseEntity<Map<String, Object>> error(final S4Exception e)
	{
		final int status = e.getStatus() >= 400 && e.getStatus() < 600 ? e.getStatus() : 502;
		final Map<String, Object> error = new LinkedHashMap<>();
		error.put("type", "S4ReturnError");
		error.put("message", e.getMessage());
		final List<Map<String, Object>> errors = new ArrayList<>();
		errors.add(error);
		final Map<String, Object> body = new LinkedHashMap<>();
		body.put("errors", errors);
		return ResponseEntity.status(status).body(body);
	}

	public void setUserService(final UserService userService)
	{
		this.userService = userService;
	}

	public void setS4Client(final S4ODataClient s4Client)
	{
		this.s4Client = s4Client;
	}
}
