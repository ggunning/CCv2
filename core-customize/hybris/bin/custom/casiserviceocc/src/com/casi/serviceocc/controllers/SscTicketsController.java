package com.casi.serviceocc.controllers;

import com.casi.serviceocc.CustomerContext;
import com.casi.serviceocc.ssc.SscCaseClient;
import com.casi.serviceocc.ssc.SscCaseClient.Entity;
import com.casi.serviceocc.ssc.SscCaseClient.SscException;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
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
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Self-service tickets: the cases of the customer's company in SAP Sales and Service Cloud Version 2,
 * read and written live (nothing is stored in Commerce).
 *
 * Every call is limited to cases of the company's account (see CustomerContext). A customer sees the
 * case description and the notes that are not internal; a message from the shop becomes a note of
 * type S000. What the shop writes starts with a line that says who wrote it and, for a new ticket,
 * may be followed by a line that says which document it is about ("Regarding: Invoice 90000004").
 *
 * GET  /occ/v2/{baseSiteId}/users/{userId}/ssctickets                         list + the case types on offer
 * POST /occ/v2/{baseSiteId}/users/{userId}/ssctickets                         {"type":"ZSMP","subject":"...","message":"...",
 *                                                                              "reference":{"type":"invoice","id":"90000004"}}
 * GET  /occ/v2/{baseSiteId}/users/{userId}/ssctickets/{ticketCode}            one ticket with its messages
 * POST /occ/v2/{baseSiteId}/users/{userId}/ssctickets/{ticketCode}/messages   {"message":"..."}
 * POST /occ/v2/{baseSiteId}/users/{userId}/ssctickets/{ticketCode}/status     {"action":"close"} or {"action":"reopen"}
 *
 * A ticket has the case number as "id" (what people see) and the case's UUID as "code" (what the
 * paths use): a case is read by its UUID directly, while searching by number only finds a new case
 * some seconds after it was created.
 */
@Controller
@RequestMapping(value = "/{baseSiteId}/users/{userId}/ssctickets")
public class SscTicketsController
{
	private static final Pattern TICKET_CODE = Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
	private static final Pattern CODE = Pattern.compile("[0-9A-Za-z_]{1,20}");
	private static final Pattern REFERENCE_ID = Pattern.compile("[0-9A-Za-z_\\-./]{1,40}");
	/** First line of what the shop writes: "Name (login) wrote in the Shop:". */
	private static final Pattern AUTHOR_LINE = Pattern.compile("\\A(.{1,150}?) \\(([^()\\s]{1,255})\\) wrote in the (.{1,60}?):\\s*\\n");
	/** Optional next line of a description: "Regarding: Invoice 90000004". */
	private static final Pattern REFERENCE_LINE = Pattern.compile("\\ARegarding: ([A-Za-z ]{1,30}) ([0-9A-Za-z_\\-./]{1,40})\\s*(\\n|\\z)");
	/** What a ticket can be about: the reference type the storefront sends, and how the line names it. */
	private static final Map<String, String> REFERENCE_LABELS = referenceLabels();
	private static final int MAX_SUBJECT = 255;
	private static final int MAX_MESSAGE = 5000;
	private static final String DESCRIPTION_NOTE_TYPE = "S001";
	private static final String MESSAGE_NOTE_TYPE = "S000";

	private CustomerContext customerContext;
	private SscCaseClient sscClient;

	@Secured({ "ROLE_CUSTOMERGROUP", "ROLE_TRUSTED_CLIENT", "ROLE_CUSTOMERMANAGERGROUP" })
	@GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	@ResponseBody
	public ResponseEntity<Map<String, Object>> getTickets(@PathVariable("baseSiteId") final String baseSiteId,
			@PathVariable("userId") final String userId)
	{
		try
		{
			final Map<String, String> account = customerContext.account();
			final JsonNode cases = sscClient.get("case-service/cases?$top=100&$orderby=" + SscCaseClient.encode("adminData.createdOn desc")
					+ "&$filter=" + SscCaseClient.encode("account.id eq " + account.get("id")));
			final List<Map<String, Object>> tickets = new ArrayList<>();
			for (final JsonNode row : cases)
			{
				tickets.add(toTicket(row, false));
			}
			final Map<String, Object> company = new LinkedHashMap<>();
			company.put("id", account.get("displayId"));
			company.put("name", account.get("name"));
			final Map<String, Object> body = new LinkedHashMap<>();
			body.put("account", company);
			body.put("types", caseTypes());
			body.put("tickets", tickets);
			return ResponseEntity.ok(body);
		}
		catch (final SscException e)
		{
			return error(e);
		}
	}

	@Secured({ "ROLE_CUSTOMERGROUP", "ROLE_TRUSTED_CLIENT", "ROLE_CUSTOMERMANAGERGROUP" })
	@GetMapping(value = "/{ticketCode}", produces = MediaType.APPLICATION_JSON_VALUE)
	@ResponseBody
	public ResponseEntity<Map<String, Object>> getTicket(@PathVariable("baseSiteId") final String baseSiteId,
			@PathVariable("userId") final String userId, @PathVariable("ticketCode") final String ticketCode)
	{
		try
		{
			return ResponseEntity.ok(toTicket(findCase(ticketCode).getValue(), true));
		}
		catch (final SscException e)
		{
			return error(e);
		}
	}

	@Secured({ "ROLE_CUSTOMERGROUP", "ROLE_TRUSTED_CLIENT", "ROLE_CUSTOMERMANAGERGROUP" })
	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	@ResponseBody
	public ResponseEntity<Map<String, Object>> createTicket(@PathVariable("baseSiteId") final String baseSiteId,
			@PathVariable("userId") final String userId, @RequestBody final Map<String, Object> request)
	{
		try
		{
			final Map<String, String> account = customerContext.account();
			final String subject = text(request.get("subject"), "a subject", MAX_SUBJECT).replaceAll("\\s+", " ");
			final String message = referenceLine(request.get("reference")) + text(request.get("message"), "a description", MAX_MESSAGE);
			final String type = String.valueOf(request.get("type"));
			if (!CODE.matcher(type).matches() || caseTypes().stream().noneMatch(t -> type.equals(t.get("code"))))
			{
				throw new SscException(400, "Choose one of the ticket types on offer");
			}

			final Map<String, Object> description = new LinkedHashMap<>();
			description.put("noteId", createNote(message, DESCRIPTION_NOTE_TYPE));
			description.put("content", signed(message));
			final Map<String, Object> body = new LinkedHashMap<>();
			body.put("subject", subject);
			body.put("caseType", type);
			body.put("origin", sscClient.setting("origin", "SELF_SERVICE"));
			body.put("priority", sscClient.setting("priority", "03"));
			body.put("account", Map.of("id", account.get("id")));
			body.put("processor", Map.of("id", processorId()));
			body.put("description", description);
			final JsonNode created = sscClient.post("case-service/cases", body);
			return ResponseEntity.status(HttpStatus.CREATED).body(toTicket(created, true));
		}
		catch (final SscException e)
		{
			return error(e);
		}
	}

	@Secured({ "ROLE_CUSTOMERGROUP", "ROLE_TRUSTED_CLIENT", "ROLE_CUSTOMERMANAGERGROUP" })
	@PostMapping(value = "/{ticketCode}/messages", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	@ResponseBody
	public ResponseEntity<Map<String, Object>> addMessage(@PathVariable("baseSiteId") final String baseSiteId,
			@PathVariable("userId") final String userId, @PathVariable("ticketCode") final String ticketCode,
			@RequestBody final Map<String, Object> request)
	{
		try
		{
			final String message = text(request.get("message"), "a message", MAX_MESSAGE);
			final Entity current = findCase(ticketCode);
			if (!isOpen(current.getValue()))
			{
				throw new SscException(400, "This ticket is completed. Reopen it or open a new ticket");
			}
			final String noteId = createNote(message, MESSAGE_NOTE_TYPE);
			// The notes of a case are replaced as a whole: send the ones it has plus the new one.
			final List<Map<String, Object>> notes = new ArrayList<>();
			for (final JsonNode note : current.getValue().path("notes"))
			{
				notes.add(Map.of("noteId", note.path("noteId").asText()));
			}
			notes.add(Map.of("noteId", noteId));
			final Map<String, Object> body = new LinkedHashMap<>();
			body.put("notes", notes);
			final JsonNode updated = sscClient.patch("case-service/cases/" + current.getValue().path("id").asText(), body,
					current.getEtag());
			return ResponseEntity.ok(toTicket(updated, true));
		}
		catch (final SscException e)
		{
			return error(e);
		}
	}

	@Secured({ "ROLE_CUSTOMERGROUP", "ROLE_TRUSTED_CLIENT", "ROLE_CUSTOMERMANAGERGROUP" })
	@PostMapping(value = "/{ticketCode}/status", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	@ResponseBody
	public ResponseEntity<Map<String, Object>> changeStatus(@PathVariable("baseSiteId") final String baseSiteId,
			@PathVariable("userId") final String userId, @PathVariable("ticketCode") final String ticketCode,
			@RequestBody final Map<String, Object> request)
	{
		try
		{
			final String action = String.valueOf(request.get("action"));
			final Entity current = findCase(ticketCode);
			final String status;
			if ("close".equals(action) && isOpen(current.getValue()))
			{
				status = sscClient.setting("closeStatus", "05");
			}
			else if ("reopen".equals(action) && canReopen(current.getValue()))
			{
				status = sscClient.setting("reopenStatus", "02");
			}
			else
			{
				throw new SscException(400, "This ticket cannot be " + ("reopen".equals(action) ? "reopened" : "closed") + " in its current status");
			}
			final Map<String, Object> body = new LinkedHashMap<>();
			body.put("status", status);
			final JsonNode updated = sscClient.patch("case-service/cases/" + current.getValue().path("id").asText(), body,
					current.getEtag());
			return ResponseEntity.ok(toTicket(updated, true));
		}
		catch (final SscException e)
		{
			return error(e);
		}
	}

	/** The case with this UUID (and its ETag), when it belongs to the caller's company. */
	private Entity findCase(final String ticketCode)
	{
		if (ticketCode == null || !TICKET_CODE.matcher(ticketCode).matches())
		{
			throw new SscException(400, "Invalid ticket code");
		}
		final String accountId = customerContext.account().get("id");
		final Entity found;
		try
		{
			found = sscClient.getEntity("case-service/cases/" + ticketCode);
		}
		catch (final SscException e)
		{
			throw e.getStatus() == 404 ? new SscException(404, "Ticket not found") : e;
		}
		if (!accountId.equals(found.getValue().path("account").path("id").asText()))
		{
			throw new SscException(404, "Ticket not found");
		}
		return found;
	}

	/** The case types on offer (settings "caseTypes"), with their names from Sales and Service Cloud. */
	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> caseTypes()
	{
		final Object cached = customerContext.cached("caseTypes");
		if (cached != null)
		{
			return (List<Map<String, Object>>) cached;
		}
		final Map<String, String> names = new LinkedHashMap<>();
		for (final JsonNode type : sscClient.get("case-type-service/caseTypes"))
		{
			names.put(type.path("code").asText(), type.path("description").asText());
		}
		final List<Map<String, Object>> types = new ArrayList<>();
		for (final String code : Arrays.asList(sscClient.setting("caseTypes", "").split(",")))
		{
			if (names.containsKey(code.trim()))
			{
				final Map<String, Object> type = new LinkedHashMap<>();
				type.put("code", code.trim());
				type.put("name", names.get(code.trim()));
				types.add(type);
			}
		}
		customerContext.cache("caseTypes", types);
		return types;
	}

	/** ID of the employee who becomes the processor of new cases (setting "processor" = employee display ID). */
	private String processorId()
	{
		final Object cached = customerContext.cached("processor");
		if (cached != null)
		{
			return (String) cached;
		}
		final String employee = sscClient.setting("processor", "");
		if (!CODE.matcher(employee).matches())
		{
			throw new SscException(500, "No processor is configured for new tickets");
		}
		final JsonNode employees = sscClient.get("employee-service/employees?$top=1&$filter="
				+ SscCaseClient.encode("employeeDisplayId eq '" + employee + "'"));
		if (!employees.isArray() || employees.isEmpty())
		{
			throw new SscException(500, "Processor " + employee + " is not an employee in Sales and Service Cloud");
		}
		final String id = employees.get(0).path("id").asText();
		customerContext.cache("processor", id);
		return id;
	}

	private String createNote(final String message, final String noteType)
	{
		final String plain = signed(message);
		final Map<String, Object> note = new LinkedHashMap<>();
		note.put("htmlContent", "<p>" + escapeHtml(plain).replace("\n", "<br>") + "</p>");
		note.put("plainContent", plain);
		note.put("languageCode", "EN");
		note.put("noteTypeCode", noteType);
		return sscClient.post("note-service/notes", note).path("id").asText();
	}

	/** The message with a first line that says who wrote it; agents read it in Sales and Service Cloud. */
	private String signed(final String message)
	{
		return customerContext.signature() + " wrote in the " + sscClient.setting("shopName", "CASI Shop") + ":\n\n" + message;
	}

	/** "Regarding: Invoice 90000004" plus an empty line, or nothing when the ticket is about no document. */
	private static String referenceLine(final Object reference)
	{
		if (!(reference instanceof Map))
		{
			return "";
		}
		final String type = String.valueOf(((Map<?, ?>) reference).get("type"));
		final String id = String.valueOf(((Map<?, ?>) reference).get("id"));
		if (!REFERENCE_LABELS.containsKey(type) || !REFERENCE_ID.matcher(id).matches())
		{
			throw new SscException(400, "This ticket refers to something the shop does not know");
		}
		return "Regarding: " + REFERENCE_LABELS.get(type) + " " + id + "\n\n";
	}

	private static Map<String, String> referenceLabels()
	{
		final Map<String, String> labels = new LinkedHashMap<>();
		labels.put("order", "Order");
		labels.put("invoice", "Invoice");
		labels.put("return", "Return");
		labels.put("quotation", "Quotation");
		labels.put("contract", "Contract");
		labels.put("delivery", "Delivery");
		labels.put("product", "Product");
		return labels;
	}

	private static boolean isOpen(final JsonNode row)
	{
		final String status = row.path("lifeCycleStatus").asText("");
		return !"COMPLETED".equals(status) && !"CLOSED".equals(status);
	}

	private static boolean canReopen(final JsonNode row)
	{
		return "COMPLETED".equals(row.path("lifeCycleStatus").asText(""));
	}

	private static Map<String, Object> toTicket(final JsonNode row, final boolean withMessages)
	{
		final Map<String, Object> ticket = new LinkedHashMap<>();
		ticket.put("code", row.path("id").asText());
		ticket.put("id", row.path("displayId").asText());
		ticket.put("subject", row.path("subject").asText(""));
		ticket.put("type", row.path("caseType").asText(""));
		ticket.put("typeName", row.path("caseTypeDescription").asText(""));
		ticket.put("status", row.path("lifeCycleStatus").asText(""));
		ticket.put("statusName", row.path("statusDescription").asText(""));
		ticket.put("priorityName", row.path("priorityDescription").asText(""));
		ticket.put("processor", row.path("processor").path("name").asText(""));
		ticket.put("createdOn", row.path("adminData").path("createdOn").asText(null));
		ticket.put("changedOn", row.path("adminData").path("updatedOn").asText(null));
		ticket.put("canReply", isOpen(row));
		ticket.put("canClose", isOpen(row));
		ticket.put("canReopen", canReopen(row));
		final Map<String, Object> first = row.path("description").hasNonNull("content") ? toMessage(row.path("description")) : null;
		if (first != null && first.get("reference") != null)
		{
			ticket.put("reference", first.get("reference"));
		}
		if (withMessages)
		{
			final List<Map<String, Object>> messages = new ArrayList<>();
			if (first != null)
			{
				first.remove("reference");
				messages.add(first);
			}
			final List<Map<String, Object>> notes = new ArrayList<>();
			for (final JsonNode note : row.path("notes"))
			{
				// Internal comments stay in Sales and Service Cloud.
				if (!note.path("isInternal").asBoolean(true))
				{
					final Map<String, Object> message = toMessage(note);
					message.remove("reference");
					notes.add(message);
				}
			}
			notes.sort(Comparator.comparing(m -> String.valueOf(m.get("createdOn"))));
			messages.addAll(notes);
			ticket.put("messages", messages);
		}
		return ticket;
	}

	/** A note as a message; "reference" is set when the text (after the author line) names a document. */
	private static Map<String, Object> toMessage(final JsonNode note)
	{
		String content = note.path("content").asText("").replace("\r\n", "\n");
		final Matcher author = AUTHOR_LINE.matcher(content);
		final boolean fromShop = author.find();
		final Map<String, Object> message = new LinkedHashMap<>();
		message.put("author", fromShop ? author.group(1) : note.path("adminData").path("createdByName").asText(""));
		message.put("fromCustomer", fromShop);
		if (fromShop)
		{
			content = content.substring(author.end()).strip();
			final Matcher reference = REFERENCE_LINE.matcher(content);
			if (reference.find())
			{
				for (final Map.Entry<String, String> label : REFERENCE_LABELS.entrySet())
				{
					if (label.getValue().equals(reference.group(1)))
					{
						final Map<String, Object> about = new LinkedHashMap<>();
						about.put("type", label.getKey());
						about.put("id", reference.group(2));
						about.put("label", label.getValue() + " " + reference.group(2));
						message.put("reference", about);
						content = content.substring(reference.end());
					}
				}
			}
		}
		message.put("text", content.strip());
		message.put("createdOn", note.path("adminData").path("createdOn").asText(null));
		return message;
	}

	private static String text(final Object value, final String what, final int max)
	{
		final String text = value == null ? "" : String.valueOf(value).replace("\r\n", "\n").replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").strip();
		if (text.isEmpty())
		{
			throw new SscException(400, "Enter " + what);
		}
		if (text.length() > max)
		{
			throw new SscException(400, "Shorten " + what.replaceFirst("^an? ", "the ") + " to " + max + " characters");
		}
		return text;
	}

	private static String escapeHtml(final String value)
	{
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	private static ResponseEntity<Map<String, Object>> error(final SscException e)
	{
		return CustomerContext.error(e.getStatus(), "SscTicketError", e.getMessage());
	}

	public void setCustomerContext(final CustomerContext customerContext)
	{
		this.customerContext = customerContext;
	}

	public void setSscClient(final SscCaseClient sscClient)
	{
		this.sscClient = sscClient;
	}
}
