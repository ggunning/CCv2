package com.casi.serviceocc.controllers;

import com.casi.returnsocc.s4.S4ODataClient;
import com.casi.returnsocc.s4.S4ODataClient.S4Exception;
import com.casi.serviceocc.CustomerContext;
import com.casi.serviceocc.ssc.SscCaseClient;
import com.casi.serviceocc.ssc.SscCaseClient.SscException;
import com.fasterxml.jackson.databind.JsonNode;
import de.hybris.platform.util.Config;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Self-service documents: what the customer's company has in SAP S/4HANA and in SAP Sales and Service
 * Cloud, read live as plain tables (nothing is stored in Commerce). One list of sources below says
 * where each kind of document comes from, how it is limited to the company and which columns it shows;
 * a new kind is one more entry.
 *
 * S/4HANA documents are read through consumed destinations (OData V2 service roots, like the returns
 * extension) and are limited by SoldToParty = the company's customer number. Sales and Service Cloud
 * documents are limited by the company's account. A source the technical user may not read answers 502
 * with a plain message; the property casiserviceocc.documents says which sources the shop offers.
 *
 * GET /occ/v2/{baseSiteId}/users/{userId}/selfservice            the kinds of documents on offer
 * GET /occ/v2/{baseSiteId}/users/{userId}/selfservice/{kind}     columns and rows of one kind (newest first, 100 at most)
 */
@Controller
@RequestMapping(value = "/{baseSiteId}/users/{userId}/selfservice")
public class SelfServiceController
{
	private static final String S4 = "SAP S/4HANA";
	private static final String SSC = "SAP Sales and Service Cloud";
	private static final Pattern ODATA_DATE = Pattern.compile("/Date\\((-?\\d+)");
	private static final String DEFAULT_KINDS = "products,installedbase,invoices,returns,quotations,sscorders";
	private static final List<Source> SOURCES = sources();

	private CustomerContext customerContext;
	private SscCaseClient sscClient;
	private S4ODataClient s4Client;

	/** One column: where its value is in a row ("a.b" for nested values) and how it is shown. */
	private static final class Column
	{
		private final String key;
		private final String label;
		/** text, date, money (extra = the currency field) or status (extra = "A=Open,B=In process"). */
		private final String type;
		private final String field;
		private final String extra;

		Column(final String label, final String type, final String field, final String extra)
		{
			this.key = field.replace('.', '_');
			this.label = label;
			this.type = type;
			this.field = field;
			this.extra = extra;
		}
	}

	/** One kind of document. */
	private static final class Source
	{
		private final String key;
		private final String title;
		private final String system;
		/** S/4: consumed destination id (property casiserviceocc.destination.key overrides it). */
		private final String destination;
		/** S/4: entity set below the destination's service root. Sales and Service Cloud: path below the API root. */
		private final String path;
		/** $filter that limits the rows to the company: {company} = customer number (S/4) or account ID (Sales and Service Cloud). */
		private final String companyFilter;
		private final String orderBy;
		private final String idField;
		/** What a ticket about one of these is called (see SscTicketsController), or null. */
		private final String referenceType;
		private final List<Column> columns;

		Source(final String key, final String title, final String system, final String destination, final String path,
				final String companyFilter, final String orderBy, final String idField, final String referenceType,
				final Column... columns)
		{
			this.key = key;
			this.title = title;
			this.system = system;
			this.destination = destination;
			this.path = path;
			this.companyFilter = companyFilter;
			this.orderBy = orderBy;
			this.idField = idField;
			this.referenceType = referenceType;
			this.columns = Arrays.asList(columns);
		}
	}

	private static List<Source> sources()
	{
		final String sdStatus = "A=Open,B=In process,C=Completed";
		final List<Source> sources = new ArrayList<>();
		// The registered product and installed base services want account/id and a quoted value.
		sources.add(new Source("products", "Your products", SSC, null, "registered-product-service/registeredProducts",
				"account/id eq '{company}'", "adminData.createdOn desc", "serialId", "product",
				new Column("Serial number", "text", "serialId", null),
				new Column("Product", "text", "referenceProduct.description", null),
				new Column("Description", "text", "description", null),
				new Column("Warranty", "text", "warranty.name", null),
				new Column("Registered on", "date", "referenceDate", null),
				new Column("Status", "status", "status", null)));
		sources.add(new Source("installedbase", "Installations", SSC, null, "installed-base-service/installedBases",
				"account/id eq '{company}'", "adminData.createdOn desc", "displayId", null,
				new Column("Installation", "text", "displayId", null),
				new Column("Description", "text", "description", null),
				new Column("Status", "status", "status", null)));
		sources.add(new Source("invoices", "Invoices", S4, "billingDestination", "A_BillingDocument", "SoldToParty eq '{company}'",
				"BillingDocument desc", "BillingDocument", "invoice",
				new Column("Invoice", "text", "BillingDocument", null),
				new Column("Type", "status", "BillingDocumentType", "F2=Invoice,S1=Cancellation,G2=Credit memo,L2=Debit memo"),
				new Column("Date", "date", "BillingDocumentDate", null),
				new Column("Net amount", "money", "TotalNetAmount", "TransactionCurrency"),
				new Column("Total", "money", "TotalGrossAmount", "TransactionCurrency")));
		sources.add(new Source("returns", "Returns", S4, "casiCustomerReturnDestination", "A_CustomerReturn", "SoldToParty eq '{company}'",
				"CustomerReturn desc", "CustomerReturn", "return",
				new Column("Return", "text", "CustomerReturn", null),
				new Column("Date", "date", "CustomerReturnDate", null),
				new Column("For order", "text", "ReferenceSDDocument", null),
				new Column("Your reference", "text", "PurchaseOrderByCustomer", null),
				new Column("Net amount", "money", "TotalNetAmount", "TransactionCurrency"),
				new Column("Status", "status", "OverallSDProcessStatus", sdStatus)));
		sources.add(new Source("quotations", "Quotations", S4, "casiSalesQuotationDestination", "A_SalesQuotation", "SoldToParty eq '{company}'",
				"SalesQuotation desc", "SalesQuotation", "quotation",
				new Column("Quotation", "text", "SalesQuotation", null),
				new Column("Date", "date", "SalesQuotationDate", null),
				new Column("Valid until", "date", "BindingPeriodValidityEndDate", null),
				new Column("Your reference", "text", "PurchaseOrderByCustomer", null),
				new Column("Net amount", "money", "TotalNetAmount", "TransactionCurrency"),
				new Column("Status", "status", "OverallSDProcessStatus", sdStatus)));
		sources.add(new Source("contracts", "Contracts", S4, "casiSalesContractDestination", "A_SalesContract", "SoldToParty eq '{company}'",
				"SalesContract desc", "SalesContract", "contract",
				new Column("Contract", "text", "SalesContract", null),
				new Column("Valid from", "date", "SalesContractValidityStartDate", null),
				new Column("Valid until", "date", "SalesContractValidityEndDate", null),
				new Column("Your reference", "text", "PurchaseOrderByCustomer", null),
				new Column("Net amount", "money", "TotalNetAmount", "TransactionCurrency"),
				new Column("Status", "status", "OverallSDProcessStatus", sdStatus)));
		sources.add(new Source("deliveries", "Deliveries", S4, "casiOutboundDeliveryDestination", "A_OutbDeliveryHeader", "SoldToParty eq '{company}'",
				"DeliveryDocument desc", "DeliveryDocument", "delivery",
				new Column("Delivery", "text", "DeliveryDocument", null),
				new Column("Planned for", "date", "DeliveryDate", null),
				new Column("Shipped on", "date", "ActualGoodsMovementDate", null),
				new Column("Shipping point", "text", "ShippingPoint", null),
				new Column("Shipping", "status", "OverallGoodsMovementStatus", "A=Not shipped yet,B=Partly shipped,C=Shipped")));
		sources.add(new Source("sscorders", "Orders with our sales team", SSC, null, "sales-order-service/salesOrders", "account.id eq {company}",
				"adminData.createdOn desc", "displayId", null,
				new Column("Order", "text", "displayId", null),
				new Column("Description", "text", "name", null),
				new Column("Date", "date", "postingDate", null),
				new Column("Status", "status", "status", null),
				new Column("Delivery", "status", "deliveryStatus", null),
				new Column("Billing", "status", "billingStatus", null),
				new Column("Order in the shop", "text", "externalId.displayId", null)));
		sources.add(new Source("appointments", "Appointments", SSC, null, "appointment-service/appointments", "account.id eq {company}",
				"startDateTime desc", "displayId", null,
				new Column("Appointment", "text", "displayId", null),
				new Column("Subject", "text", "subject", null),
				new Column("Starts", "datetime", "startDateTime", null),
				new Column("Ends", "datetime", "endDateTime", null),
				new Column("With", "text", "owner.name", null),
				new Column("Status", "status", "status", null)));
		return sources;
	}

	@Secured({ "ROLE_CUSTOMERGROUP", "ROLE_TRUSTED_CLIENT", "ROLE_CUSTOMERMANAGERGROUP" })
	@GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	@ResponseBody
	public ResponseEntity<Map<String, Object>> getKinds(@PathVariable("baseSiteId") final String baseSiteId,
			@PathVariable("userId") final String userId)
	{
		try
		{
			customerContext.customerNumber();
			final List<Map<String, Object>> kinds = new ArrayList<>();
			for (final Source source : offered())
			{
				final Map<String, Object> kind = new LinkedHashMap<>();
				kind.put("key", source.key);
				kind.put("title", source.title);
				kind.put("source", source.system);
				kinds.add(kind);
			}
			final Map<String, Object> body = new LinkedHashMap<>();
			body.put("kinds", kinds);
			return ResponseEntity.ok(body);
		}
		catch (final SscException e)
		{
			return CustomerContext.error(e.getStatus(), "SelfServiceError", e.getMessage());
		}
	}

	@Secured({ "ROLE_CUSTOMERGROUP", "ROLE_TRUSTED_CLIENT", "ROLE_CUSTOMERMANAGERGROUP" })
	@GetMapping(value = "/{kind}", produces = MediaType.APPLICATION_JSON_VALUE)
	@ResponseBody
	public ResponseEntity<Map<String, Object>> getDocuments(@PathVariable("baseSiteId") final String baseSiteId,
			@PathVariable("userId") final String userId, @PathVariable("kind") final String kind)
	{
		Source source = null;
		for (final Source candidate : offered())
		{
			if (candidate.key.equals(kind))
			{
				source = candidate;
			}
		}
		if (source == null)
		{
			return CustomerContext.error(404, "SelfServiceError", "Unknown kind of document");
		}
		try
		{
			final JsonNode rows = S4.equals(source.system) ? s4Rows(source) : sscRows(source);
			final List<Map<String, Object>> columns = new ArrayList<>();
			for (final Column column : source.columns)
			{
				final Map<String, Object> head = new LinkedHashMap<>();
				head.put("key", column.key);
				head.put("label", column.label);
				head.put("type", "date".equals(column.type) || "datetime".equals(column.type) ? column.type : "text");
				columns.add(head);
			}
			final List<Map<String, Object>> documents = new ArrayList<>();
			for (final JsonNode row : rows)
			{
				final Map<String, Object> values = new LinkedHashMap<>();
				for (final Column column : source.columns)
				{
					values.put(column.key, value(row, column));
				}
				final Map<String, Object> document = new LinkedHashMap<>();
				document.put("id", at(row, source.idField).asText(""));
				document.put("values", values);
				documents.add(document);
			}
			final Map<String, Object> body = new LinkedHashMap<>();
			body.put("key", source.key);
			body.put("title", source.title);
			body.put("source", source.system);
			body.put("referenceType", source.referenceType);
			body.put("columns", columns);
			body.put("documents", documents);
			return ResponseEntity.ok(body);
		}
		catch (final S4Exception e)
		{
			// Whatever S/4 answers (also 401/403: that is our technical user), the customer cannot act on it.
			return CustomerContext.error(502, "SelfServiceError", source.title + " are not available at the moment");
		}
		catch (final SscException e)
		{
			final boolean own = e.getStatus() == 403 || e.getStatus() == 404;
			return CustomerContext.error(own ? e.getStatus() : 502, "SelfServiceError",
					own ? e.getMessage() : source.title + " are not available at the moment");
		}
	}

	private JsonNode s4Rows(final Source source)
	{
		final String destination = Config.getString("casiserviceocc.destination." + source.key, source.destination);
		final String filter = source.companyFilter.replace("{company}", customerContext.customerNumber());
		return s4Client.get(destination, source.path + "?$format=json&$top=100&$orderby=" + SscCaseClient.encode(source.orderBy)
				+ "&$filter=" + SscCaseClient.encode(filter)).path("results");
	}

	private JsonNode sscRows(final Source source)
	{
		final String filter = source.companyFilter.replace("{company}", customerContext.account().get("id"));
		return sscClient.get(source.path + "?$top=100&$orderby=" + SscCaseClient.encode(source.orderBy) + "&$filter="
				+ SscCaseClient.encode(filter));
	}

	/** The sources named in casiserviceocc.documents, in that order. */
	private static List<Source> offered()
	{
		final List<Source> offered = new ArrayList<>();
		for (final String key : Config.getString("casiserviceocc.documents", DEFAULT_KINDS).split(","))
		{
			for (final Source source : SOURCES)
			{
				if (source.key.equals(key.trim()))
				{
					offered.add(source);
				}
			}
		}
		return offered;
	}

	/** A cell as text; dates as yyyy-MM-dd and datetimes as ISO strings for the storefront to format. */
	private static String value(final JsonNode row, final Column column)
	{
		final String raw = at(row, column.field).asText("");
		if (raw.isEmpty())
		{
			return "";
		}
		if ("date".equals(column.type) || "datetime".equals(column.type))
		{
			final Matcher odata = ODATA_DATE.matcher(raw);
			final String iso = odata.find() ? Instant.ofEpochMilli(Long.parseLong(odata.group(1))).toString() : raw;
			// A date is a calendar day (yyyy-MM-dd), so no time zone can move it; a datetime is a moment.
			return "date".equals(column.type) && iso.length() > 10 ? iso.substring(0, 10) : iso;
		}
		if ("money".equals(column.type))
		{
			return (raw + " " + at(row, column.extra).asText("")).strip();
		}
		if ("status".equals(column.type))
		{
			if (column.extra != null)
			{
				for (final String pair : column.extra.split(","))
				{
					if (pair.startsWith(raw + "="))
					{
						return pair.substring(raw.length() + 1);
					}
				}
				return raw;
			}
			// NOT_STARTED -> Not started
			final String words = raw.replace('_', ' ').toLowerCase(Locale.ROOT);
			return words.substring(0, 1).toUpperCase(Locale.ROOT) + words.substring(1);
		}
		return raw;
	}

	private static JsonNode at(final JsonNode row, final String field)
	{
		JsonNode node = row;
		for (final String part : field.split("\\."))
		{
			node = node.path(part);
		}
		return node;
	}

	public void setCustomerContext(final CustomerContext customerContext)
	{
		this.customerContext = customerContext;
	}

	public void setSscClient(final SscCaseClient sscClient)
	{
		this.sscClient = sscClient;
	}

	public void setS4Client(final S4ODataClient s4Client)
	{
		this.s4Client = s4Client;
	}
}
