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
 *   {"code":"...", "password":"...",
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
 * The endpoint is public (a visitor has no session), so it is closed unless a registration code is
 * configured (setting "registrationCode") and sent with the request. A person who joins a company
 * that already exists in S/4 gets a login that stays disabled until someone enables it in Backoffice:
 * a VAT ID is public, and the login shows that company's tickets and documents.
 */
@Controller
@RequestMapping(value = "/{baseSiteId}/orgregistrations")
public class OrgRegistrationController
{
	private static final String BP_DESTINATION = "casiBusinessPartnerDestination";
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
			checkCode(request.get("code"));
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
				steps.add(step("S/4HANA", "Company created as customer " + customerNumber, true));
			}
			else
			{
				steps.add(step("S/4HANA", "Company found as customer " + customerNumber, true));
			}

			// 2. Commerce: unit and user (search restrictions hide units from a visitor, so they are off here)
			final String number = customerNumber;
			final Object created = sessionService.executeInLocalView(new SessionExecutionBody()
			{
				@Override
				public Object execute()
				{
					searchRestrictionService.disableSearchRestrictions();
					return createInCommerce(data, number, newCompany);
				}
			});
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
			// Joining an existing company needs someone's approval; see the class comment.
			body.put("loginEnabled", newCompany);
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

	/** The registration code must be configured and must match; without a configured code the endpoint is closed. */
	private void checkCode(final Object code)
	{
		final String expected = sscClient.setting("registrationCode", "");
		if (expected.isEmpty())
		{
			throw new SscException(403, "Registration is not open in this shop");
		}
		if (code == null || !expected.equals(String.valueOf(code).strip()))
		{
			throw new SscException(403, "The registration code is not correct");
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
		company.put("to_Customer", Map.of("to_CustomerSalesArea", List.of(salesArea)));
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
		s4Client.post(BP_DESTINATION, "A_BPRelationship", relationship);
		return partner;
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
		}

		final Set<PrincipalGroupModel> groups = new HashSet<>();
		groups.add(unit);
		groups.add(userService.getUserGroupForUID("b2bcustomergroup"));
		final B2BCustomerModel customer = modelService.create(B2BCustomerModel.class);
		customer.setUid(data.email);
		customer.setOriginalUid(data.email);
		customer.setEmail(data.email);
		customer.setName(data.firstName + " " + data.lastName);
		customer.setDefaultB2BUnit(unit);
		customer.setGroups(groups);
		customer.setActive(Boolean.TRUE);
		customer.setLoginDisabled(!newCompany);
		modelService.save(customer);
		userService.setPassword(customer, data.password, "argon2");
		modelService.save(customer);
		return (newUnit ? "Company " + customerNumber + " and user " : "User ") + data.email + " created"
				+ (newCompany ? "" : ", sign-in waits for approval");
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
