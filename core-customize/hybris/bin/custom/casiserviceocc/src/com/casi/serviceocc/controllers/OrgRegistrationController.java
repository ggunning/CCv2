package com.casi.serviceocc.controllers;

import com.casi.returnsocc.s4.S4ODataClient;
import com.casi.returnsocc.s4.S4ODataClient.S4Exception;
import com.casi.serviceocc.CustomerContext;
import com.casi.serviceocc.ssc.SscCaseClient;
import com.casi.serviceocc.ssc.SscCaseClient.SscException;
import com.fasterxml.jackson.databind.JsonNode;
import de.hybris.platform.b2b.model.B2BCostCenterModel;
import de.hybris.platform.b2b.model.B2BCustomerModel;
import de.hybris.platform.b2b.model.B2BUnitModel;
import de.hybris.platform.core.model.c2l.CountryModel;
import de.hybris.platform.core.model.security.PrincipalGroupModel;
import de.hybris.platform.core.model.user.AddressModel;
import de.hybris.platform.search.restriction.SearchRestrictionService;
import de.hybris.platform.servicelayer.i18n.CommonI18NService;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import de.hybris.platform.servicelayer.session.SessionExecutionBody;
import de.hybris.platform.servicelayer.session.SessionService;
import de.hybris.platform.servicelayer.user.UserService;
import de.hybris.platform.util.Config;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Demo: a company and its contact person register in the shop, and both are created in SAP S/4HANA,
 * in Commerce and in SAP Sales and Service Cloud in one go. Not SAP standard (which replicates
 * business partners through SAP Cloud Integration); it shows how the pieces can fit together.
 *
 * POST /occ/v2/{baseSiteId}/orgregistrations
 *   {"password":"...",
 *    "company":{"name":"...","street":"...","houseNumber":"...","postalCode":"...","city":"...","country":"DE","vatId":"DE123456789"},
 *    "contact":{"firstName":"...","lastName":"...","email":"..."}}
 *
 * Order of work:
 *  1. S/4: the company is looked up by its VAT ID; a company that is not there is created as a
 *     business partner with the customer role and gets its customer number.
 *  2. Commerce: B2B unit (uid = customer number), its address and cost center when the unit is new,
 *     and the B2B customer with the chosen password.
 *  3. S/4: the contact person (a business partner) and its relationship to the company.
 *  4. Sales and Service Cloud: the account (with the customer number as external ID) when it is not
 *     there yet, and the contact person.
 * Steps 1 and 2 must work; a failure in 3 or 4 does not undo them: the answer lists every step with
 * what happened, and "complete" says whether all of them worked.
 *
 * The endpoint is public: a visitor has no session. It only sets a company up; everything after that is
 * Commerce's own organization management (My Account > My Company):
 *  - The person who registers a company becomes its administrator in the shop (b2badmingroup) and adds
 *    colleagues, roles, units, cost centers and purchase limits under My Company.
 *  - Once a company has an administrator, nobody else can register for it here: the answer is 409 and says
 *    to ask the administrator (a VAT ID is public, and a login shows the company's tickets and documents).
 *  - A company that S/4 already knows but that has no administrator in the shop yet gets its first one
 *    here, with a login that stays disabled until someone enables it in Backoffice (User > Customers).
 */
@Controller
@RequestMapping(value = "/{baseSiteId}/orgregistrations")
public class OrgRegistrationController
{
	private static final String BP_DESTINATION = "casiBusinessPartnerDestination";
	/** Commerce's company administrators: they see My Company and manage the company's users. */
	private static final String ADMIN_GROUP = "b2badmingroup";
	private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+\\-]{1,64}@[A-Za-z0-9.\\-]{1,180}\\.[A-Za-z]{2,24}");
	private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");
	private static final Pattern VAT_ID = Pattern.compile("[A-Z]{2}[0-9A-Z]{2,13}");
	private static final Pattern POSTAL_CODE = Pattern.compile("[0-9A-Za-z \\-]{2,10}");
	private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
	/** Year 9999: how S/4 says "valid without end". */
	private static final String FOREVER = "/Date(253402214400000)/";

	private SscCaseClient sscClient;
	private S4ODataClient s4Client;
	private ModelService modelService;
	private UserService userService;
	private CommonI18NService commonI18NService;
	private FlexibleSearchService flexibleSearchService;
	private SessionService sessionService;
	private SearchRestrictionService searchRestrictionService;

	/** What the visitor entered, checked. */
	private static final class Registration
	{
		private String companyName;
		private String street;
		private String houseNumber;
		private String postalCode;
		private String city;
		private String country;
		private String vatId;
		private String firstName;
		private String lastName;
		private String email;
		private String password;
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	@ResponseBody
	public ResponseEntity<Map<String, Object>> register(@PathVariable("baseSiteId") final String baseSiteId,
			@RequestBody final Map<String, Object> request)
	{
		final List<Map<String, Object>> steps = new ArrayList<>();
		try
		{
			final Registration data = read(request);
			// Looked up without search restrictions: a visitor's session may not see other users.
			final Object known = sessionService.executeInLocalView(new SessionExecutionBody()
			{
				@Override
				public Object execute()
				{
					searchRestrictionService.disableSearchRestrictions();
					return Boolean.valueOf(userService.isUserExisting(data.email));
				}
			});
			if (Boolean.TRUE.equals(known))
			{
				throw new SscException(400, "This email address is already registered. Sign in instead");
			}

			// 1. S/4: the company and its customer number
			String customerNumber = data.vatId.isEmpty() ? null : findCompany(data.vatId);
			final boolean newCompany = customerNumber == null;
			if (newCompany)
			{
				customerNumber = createCompany(data);
				steps.add(step("S/4HANA", "Company created as customer " + customerNumber
						+ ", waiting for approval (delivery and billing blocked)", true));
			}
			else
			{
				steps.add(step("S/4HANA", "Company found as customer " + customerNumber, true));
			}

			// 2. Commerce: unit and user. Done as admin: the visitor is anonymous, search restrictions hide units
			// from it, and the CDC B2B extension lets only admins create a top-level unit. The input was
			// checked above; the session's own user is unchanged afterwards.
			final String number = customerNumber;
			final Object created;
			try
			{
				created = sessionService.executeInLocalView(new SessionExecutionBody()
				{
					@Override
					public Object execute()
					{
						searchRestrictionService.disableSearchRestrictions();
						return createInCommerce(data, number, newCompany);
					}
				}, userService.getAdminUser());
			}
			catch (final SscException e)
			{
				throw e;
			}
			catch (final RuntimeException e)
			{
				// Say what was already done in S/4, so the company is not created twice by a retry.
				return failure(500, "Your account could not be created in the shop: " + e.getMessage(), steps);
			}
			steps.add(step("Commerce", String.valueOf(created), true));

			// 3. and 4.: the contact person in S/4, account and contact in Sales and Service Cloud
			boolean complete = true;
			try
			{
				steps.add(step("S/4HANA", "Contact person created as business partner " + createContact(data, number), true));
			}
			catch (final S4Exception e)
			{
				complete = false;
				steps.add(step("S/4HANA", "Contact person not created: " + e.getMessage(), false));
			}
			try
			{
				steps.addAll(replicateToSalesCloud(data, number));
			}
			catch (final SscException e)
			{
				complete = false;
				steps.add(step("Sales and Service Cloud", "Not replicated: " + e.getMessage(), false));
			}

			final Map<String, Object> company = new LinkedHashMap<>();
			company.put("number", number);
			company.put("name", data.companyName);
			company.put("isNew", newCompany);
			final Map<String, Object> body = new LinkedHashMap<>();
			body.put("company", company);
			body.put("email", data.email);
			// A company S/4 already knew waits for our approval of its first administrator; see the class comment.
			body.put("loginEnabled", newCompany);
			// A new company waits for approval in S/4; for an existing one, the banner reads its state.
			body.put("approvalPending", newCompany);
			body.put("complete", complete);
			body.put("steps", steps);
			return ResponseEntity.status(HttpStatus.CREATED).body(body);
		}
		catch (final SscException e)
		{
			return failure(e.getStatus(), e.getMessage(), steps);
		}
		catch (final S4Exception e)
		{
			// Before anything was created in Commerce: S/4 refused the look-up or the new company.
			return failure(502, "The company could not be set up in S/4HANA: " + e.getMessage(), steps);
		}
	}

	private static Registration read(final Map<String, Object> request)
	{
		final Map<?, ?> company = request.get("company") instanceof Map ? (Map<?, ?>) request.get("company") : Map.of();
		final Map<?, ?> contact = request.get("contact") instanceof Map ? (Map<?, ?>) request.get("contact") : Map.of();
		final Registration data = new Registration();
		data.companyName = text(company.get("name"), "the company name", 40);
		data.street = text(company.get("street"), "the street", 60);
		data.houseNumber = optional(company.get("houseNumber"), 10);
		data.postalCode = text(company.get("postalCode"), "the postal code", 10);
		data.city = text(company.get("city"), "the city", 40);
		data.country = text(company.get("country"), "the country", 2).toUpperCase(Locale.ROOT);
		data.vatId = optional(company.get("vatId"), 15).replace(" ", "").toUpperCase(Locale.ROOT);
		data.firstName = text(contact.get("firstName"), "the first name", 40);
		data.lastName = text(contact.get("lastName"), "the last name", 40);
		data.email = text(contact.get("email"), "the email address", 240).toLowerCase(Locale.ROOT);
		data.password = request.get("password") == null ? "" : String.valueOf(request.get("password"));
		if (!COUNTRY.matcher(data.country).matches())
		{
			throw new SscException(400, "Choose a country");
		}
		if (!POSTAL_CODE.matcher(data.postalCode).matches())
		{
			throw new SscException(400, "Check the postal code");
		}
		if (!data.vatId.isEmpty() && (!VAT_ID.matcher(data.vatId).matches() || !data.vatId.startsWith(data.country)))
		{
			throw new SscException(400, "The VAT ID starts with the country code, for example " + data.country + "123456789");
		}
		if (!EMAIL.matcher(data.email).matches())
		{
			throw new SscException(400, "Check the email address");
		}
		if (data.password.length() < 10 || data.password.length() > 128)
		{
			throw new SscException(400, "Choose a password of at least 10 characters");
		}
		return data;
	}

	/** Customer number of the company with this VAT ID in S/4, or null. */
	private String findCompany(final String vatId)
	{
		final JsonNode taxNumbers = s4Client.get(BP_DESTINATION, "A_BusinessPartnerTaxNumber?$format=json&$top=1&$filter="
				+ SscCaseClient.encode("BPTaxNumber eq '" + vatId + "'")).path("results");
		if (!taxNumbers.isArray() || taxNumbers.isEmpty())
		{
			return null;
		}
		final String partner = taxNumbers.get(0).path("BusinessPartner").asText();
		final String customer = s4Client.get(BP_DESTINATION, "A_BusinessPartner('" + SscCaseClient.encode(partner)
				+ "')?$format=json&$select=Customer").path("Customer").asText("");
		if (customer.isEmpty())
		{
			throw new SscException(400, "A company with this VAT ID exists but is not a customer yet. Please contact us");
		}
		return customer;
	}

	private String createCompany(final Registration data)
	{
		final Map<String, Object> salesArea = new LinkedHashMap<>();
		salesArea.put("SalesOrganization", Config.getString("casiserviceocc.registration.salesOrganization", "DE10"));
		salesArea.put("DistributionChannel", Config.getString("casiserviceocc.registration.distributionChannel", "10"));
		salesArea.put("Division", Config.getString("casiserviceocc.registration.division", "10"));
		salesArea.put("Currency", Config.getString("casiserviceocc.registration.currency", "EUR"));
		salesArea.put("CustomerPricingProcedure", "01");
		salesArea.put("ShippingCondition", "01");
		// Without payment terms, Incoterms and a tax classification S/4 keeps every order of the customer
		// incomplete, so it can never be delivered (seen on BE6). The values are those of CASI Demo Company.
		salesArea.put("CustomerPaymentTerms", Config.getString("casiserviceocc.registration.paymentTerms", "0001"));
		salesArea.put("IncotermsClassification", Config.getString("casiserviceocc.registration.incoterms", "EXW"));
		salesArea.put("IncotermsTransferLocation", Config.getString("casiserviceocc.registration.incotermsLocation", "Walldorf"));
		salesArea.put("IncotermsLocation1", Config.getString("casiserviceocc.registration.incotermsLocation", "Walldorf"));
		final Map<String, Object> tax = new LinkedHashMap<>();
		tax.put("DepartureCountry", Config.getString("casiserviceocc.registration.taxCountry", "DE"));
		tax.put("CustomerTaxCategory", "TTX1");
		tax.put("CustomerTaxClassification", Config.getString("casiserviceocc.registration.taxClassification", "1"));
		salesArea.put("to_SalesAreaTax", List.of(tax));
		final Map<String, Object> company = new LinkedHashMap<>();
		company.put("BusinessPartnerCategory", "2");
		company.put("OrganizationBPName1", data.companyName);
		company.put("SearchTerm1", data.companyName.substring(0, Math.min(20, data.companyName.length())).toUpperCase(Locale.ROOT));
		company.put("to_BusinessPartnerRole", List.of(Map.of("BusinessPartnerRole", "FLCU00"), Map.of("BusinessPartnerRole", "FLCU01")));
		company.put("to_BusinessPartnerAddress", List.of(address(data, null)));
		if (!data.vatId.isEmpty())
		{
			// VAT registration number: tax category <country>0 (DE0, FR0, ...)
			company.put("to_BusinessPartnerTax", List.of(Map.of("BPTaxType", data.country + "0", "BPTaxNumber", data.vatId)));
		}
		// Approval happens later in S/4: until someone removes these blocks (Manage Customer Master Data),
		// the company can sign in and order, but nothing is delivered or invoiced. BE6 has no order block
		// reasons configured, and a central business partner block does not stop orders.
		final Map<String, Object> customerData = new LinkedHashMap<>();
		customerData.put("DeliveryIsBlocked", Config.getString("casiserviceocc.registration.deliveryBlock", "01"));
		customerData.put("BillingIsBlockedForCustomer", Config.getString("casiserviceocc.registration.billingBlock", "03"));
		customerData.put("to_CustomerSalesArea", List.of(salesArea));
		company.put("to_Customer", customerData);
		final JsonNode created = s4Client.post(BP_DESTINATION, "A_BusinessPartner", company);
		final String customer = created.path("Customer").asText("");
		return customer.isEmpty() ? created.path("BusinessPartner").asText() : customer;
	}

	/** The contact person as a business partner with a "contact person" relationship to the company. */
	private String createContact(final Registration data, final String customerNumber)
	{
		final Map<String, Object> person = new LinkedHashMap<>();
		person.put("BusinessPartnerCategory", "1");
		person.put("FirstName", data.firstName);
		person.put("LastName", data.lastName);
		person.put("CorrespondenceLanguage", "EN");
		person.put("to_BusinessPartnerRole", List.of(Map.of("BusinessPartnerRole", "BUP001")));
		person.put("to_BusinessPartnerAddress", List.of(address(data, data.email)));
		final String partner = s4Client.post(BP_DESTINATION, "A_BusinessPartner", person).path("BusinessPartner").asText();

		final long today = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
		final Map<String, Object> relationship = new LinkedHashMap<>();
		relationship.put("BusinessPartner1", customerNumber);
		relationship.put("BusinessPartner2", partner);
		relationship.put("RelationshipCategory", "BUR001");
		relationship.put("ValidityStartDate", "/Date(" + today + ")/");
		relationship.put("ValidityEndDate", FOREVER);
		final String relationshipNumber = s4Client.post(BP_DESTINATION, "A_BPRelationship", relationship)
				.path("RelationshipNumber").asText("");
		return partner + contactEmail(relationshipNumber, customerNumber, partner, data.email);
	}

	/**
	 * The email address on the contact relationship itself. The person's own address has it too, but S/4 shows
	 * a customer's contacts with the relationship's communication data, so without this the contact has no
	 * email there. Returns "" when set, else a remark for the step: the contact exists either way.
	 */
	private String contactEmail(final String relationshipNumber, final String customerNumber, final String partner,
			final String email)
	{
		if (relationshipNumber.isEmpty())
		{
			return " (email not set on the contact: S/4 returned no relationship number)";
		}
		try
		{
			// key as S/4 writes it: 9999-12-31 is FOREVER, the end date the relationship was created with
			s4Client.patch(BP_DESTINATION, "A_BPContactToFuncAndDept(RelationshipNumber='" + SscCaseClient.encode(relationshipNumber)
					+ "',BusinessPartnerCompany='" + SscCaseClient.encode(customerNumber) + "',BusinessPartnerPerson='"
					+ SscCaseClient.encode(partner) + "',ValidityEndDate=datetime'9999-12-31T00%3A00%3A00')",
					Map.of("EmailAddress", email));
			return "";
		}
		catch (final RuntimeException e)
		{
			return " (email not set on the contact: " + e.getMessage() + ")";
		}
	}

	private static Map<String, Object> address(final Registration data, final String email)
	{
		final Map<String, Object> address = new LinkedHashMap<>();
		address.put("Country", data.country);
		address.put("CityName", data.city);
		address.put("PostalCode", data.postalCode);
		address.put("StreetName", data.street);
		if (!data.houseNumber.isEmpty())
		{
			address.put("HouseNumber", data.houseNumber);
		}
		address.put("Language", "EN");
		if (email != null)
		{
			final Map<String, Object> mail = new LinkedHashMap<>();
			mail.put("EmailAddress", email);
			mail.put("IsDefaultEmailAddress", Boolean.TRUE);
			address.put("to_EmailAddress", List.of(mail));
		}
		return address;
	}

	/** B2B unit (with address and cost center when new) and the B2B customer. Returns what was done, in words. */
	private String createInCommerce(final Registration data, final String customerNumber, final boolean newCompany)
	{
		final CountryModel country;
		try
		{
			country = commonI18NService.getCountry(data.country);
		}
		catch (final RuntimeException e)
		{
			throw new SscException(400, "The shop does not deliver to " + data.country);
		}
		final FlexibleSearchQuery query = new FlexibleSearchQuery("SELECT {pk} FROM {B2BUnit} WHERE {uid} = ?uid");
		query.addQueryParameter("uid", customerNumber);
		final List<B2BUnitModel> units = flexibleSearchService.<B2BUnitModel> search(query).getResult();
		final B2BUnitModel unit;
		final boolean newUnit = units.isEmpty();
		if (newUnit)
		{
			unit = modelService.create(B2BUnitModel.class);
			unit.setUid(customerNumber);
			unit.setName(data.companyName);
			unit.setLocName(data.companyName, Locale.ENGLISH);
			unit.setActive(Boolean.TRUE);
			modelService.save(unit);

			final AddressModel address = modelService.create(AddressModel.class);
			address.setOwner(unit);
			address.setStreetname(data.street);
			address.setStreetnumber(data.houseNumber);
			address.setPostalcode(data.postalCode);
			address.setTown(data.city);
			address.setCountry(country);
			address.setCompany(data.companyName);
			address.setShippingAddress(Boolean.TRUE);
			address.setBillingAddress(Boolean.TRUE);
			address.setContactAddress(Boolean.TRUE);
			address.setVisibleInAddressBook(Boolean.TRUE);
			modelService.save(address);

			// Ordering on account needs a cost center of the unit.
			final B2BCostCenterModel costCenter = modelService.create(B2BCostCenterModel.class);
			costCenter.setCode("CASI-CC-" + customerNumber);
			costCenter.setName(data.companyName + " cost center", Locale.ENGLISH);
			costCenter.setUnit(unit);
			costCenter.setCurrency(commonI18NService.getCurrency(Config.getString("casiserviceocc.registration.currency", "EUR")));
			costCenter.setActive(Boolean.TRUE);
			modelService.save(costCenter);
		}
		else
		{
			unit = units.get(0);
			if (hasAdministrator(unit))
			{
				throw new SscException(409, "Your company is already registered in the shop. Please ask your company's"
						+ " administrator to add you: My Account > My Company > Users");
			}
		}

		final Set<PrincipalGroupModel> groups = new HashSet<>();
		groups.add(unit);
		groups.add(userService.getUserGroupForUID("b2bcustomergroup"));
		// The company's first user manages it (My Company); a company that has an administrator was refused above.
		groups.add(userService.getUserGroupForUID(ADMIN_GROUP));
		final B2BCustomerModel customer = modelService.create(B2BCustomerModel.class);
		customer.setUid(data.email);
		customer.setOriginalUid(data.email);
		customer.setEmail(data.email);
		customer.setName(data.firstName + " " + data.lastName);
		customer.setDefaultB2BUnit(unit);
		customer.setGroups(groups);
		customer.setActive(Boolean.TRUE);
		// A company S/4 already knew: its first administrator waits until someone enables the login in Backoffice.
		customer.setLoginDisabled(!newCompany);
		modelService.save(customer);
		userService.setPassword(customer, data.password, "argon2");
		modelService.save(customer);
		return (newUnit ? "Company " + customerNumber + " and user " : "User ") + data.email
				+ " created as the company's administrator" + (newCompany ? "" : ", sign-in waits for our approval");
	}

	/** True when a user of the unit who can sign in is a company administrator. */
	private static boolean hasAdministrator(final B2BUnitModel unit)
	{
		if (unit.getMembers() == null)
		{
			return false;
		}
		for (final Object member : unit.getMembers())
		{
			if (member instanceof B2BCustomerModel)
			{
				final B2BCustomerModel user = (B2BCustomerModel) member;
				if (Boolean.TRUE.equals(user.getActive()) && !user.isLoginDisabled() && user.getGroups() != null)
				{
					for (final PrincipalGroupModel group : user.getGroups())
					{
						if (ADMIN_GROUP.equals(group.getUid()))
						{
							return true;
						}
					}
				}
			}
		}
		return false;
	}

	/** The account (when Sales and Service Cloud does not have it yet) and the contact person. */
	private List<Map<String, Object>> replicateToSalesCloud(final Registration data, final String customerNumber)
	{
		final List<Map<String, Object>> steps = new ArrayList<>();
		final JsonNode accounts = sscClient.get("account-service/accounts?$top=1&$filter="
				+ SscCaseClient.encode("externalIds/externalId eq '" + customerNumber + "'"));
		final String accountId;
		if (accounts.isArray() && !accounts.isEmpty())
		{
			accountId = accounts.get(0).path("id").asText();
			steps.add(step("Sales and Service Cloud", "Account " + accounts.get(0).path("displayId").asText() + " found", true));
		}
		else
		{
			final Map<String, Object> address = new LinkedHashMap<>();
			address.put("isDefault", Boolean.TRUE);
			address.put("country", data.country);
			address.put("cityName", data.city);
			address.put("postalCode", data.postalCode);
			address.put("streetName", data.street);
			address.put("houseId", data.houseNumber);
			address.put("deliveryAddressRelevance", "YES");
			address.put("billToAddressRelevance", "YES");
			final Map<String, Object> account = new LinkedHashMap<>();
			account.put("formattedName", data.companyName);
			account.put("firstLineName", data.companyName);
			account.put("customerRole", "CRM000");
			account.put("isProspect", Boolean.FALSE);
			account.put("lifeCycleStatus", "ACTIVE");
			account.put("addresses", List.of(address));
			// The S/4 business partner and customer numbers, so orders, tickets and products find the account.
			final String system = sscClient.setting("s4CommunicationSystemId", "");
			if (UUID.matcher(system).matches())
			{
				account.put("externalIds", List.of(externalId(customerNumber, system, "sap.oitc.888"), externalId(customerNumber, system, "sap.oitc.918")));
			}
			final JsonNode created = sscClient.post("account-service/accounts", account);
			accountId = created.path("id").asText();
			steps.add(step("Sales and Service Cloud", "Account " + created.path("displayId").asText() + " created"
					+ (account.containsKey("externalIds") ? "" : " without the S/4 number (s4CommunicationSystemId is not set)"), true));
		}
		final Map<String, Object> contact = new LinkedHashMap<>();
		contact.put("givenName", data.firstName);
		contact.put("familyName", data.lastName);
		contact.put("accountId", accountId);
		contact.put("eMail", data.email);
		final JsonNode created = sscClient.post("contact-person-service/contactPersons", contact);
		steps.add(step("Sales and Service Cloud", "Contact person " + created.path("displayId").asText() + " created", true));
		return steps;
	}

	private static Map<String, Object> externalId(final String number, final String system, final String type)
	{
		final Map<String, Object> id = new LinkedHashMap<>();
		id.put("externalId", number);
		id.put("communicationSystemId", system);
		id.put("type", type);
		id.put("isDefault", Boolean.TRUE);
		return id;
	}

	private static Map<String, Object> step(final String system, final String result, final boolean ok)
	{
		final Map<String, Object> step = new LinkedHashMap<>();
		step.put("system", system);
		step.put("result", result);
		step.put("ok", ok);
		return step;
	}

	private static String text(final Object value, final String what, final int max)
	{
		final String text = optional(value, Integer.MAX_VALUE);
		if (text.isEmpty())
		{
			throw new SscException(400, "Enter " + what);
		}
		if (text.length() > max)
		{
			throw new SscException(400, "Shorten " + what + " to " + max + " characters");
		}
		return text;
	}

	private static String optional(final Object value, final int max)
	{
		final String text = value == null ? "" : String.valueOf(value).replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").strip();
		if (text.length() > max && max != Integer.MAX_VALUE)
		{
			throw new SscException(400, "One of the entries is too long");
		}
		return text;
	}

	private static ResponseEntity<Map<String, Object>> failure(final int status, final String message, final List<Map<String, Object>> steps)
	{
		final ResponseEntity<Map<String, Object>> error = CustomerContext.error(status, "OrgRegistrationError", message);
		final Map<String, Object> body = new LinkedHashMap<>(error.getBody());
		body.put("steps", steps);
		return ResponseEntity.status(error.getStatusCode()).body(body);
	}

	public void setSscClient(final SscCaseClient sscClient)
	{
		this.sscClient = sscClient;
	}

	public void setS4Client(final S4ODataClient s4Client)
	{
		this.s4Client = s4Client;
	}

	public void setModelService(final ModelService modelService)
	{
		this.modelService = modelService;
	}

	public void setUserService(final UserService userService)
	{
		this.userService = userService;
	}

	public void setCommonI18NService(final CommonI18NService commonI18NService)
	{
		this.commonI18NService = commonI18NService;
	}

	public void setFlexibleSearchService(final FlexibleSearchService flexibleSearchService)
	{
		this.flexibleSearchService = flexibleSearchService;
	}

	public void setSessionService(final SessionService sessionService)
	{
		this.sessionService = sessionService;
	}

	public void setSearchRestrictionService(final SearchRestrictionService searchRestrictionService)
	{
		this.searchRestrictionService = searchRestrictionService;
	}
}
