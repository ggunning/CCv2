package com.casi.serviceocc;

import com.casi.serviceocc.ssc.SscCaseClient;
import com.casi.serviceocc.ssc.SscCaseClient.SscException;
import com.fasterxml.jackson.databind.JsonNode;
import de.hybris.platform.b2b.model.B2BCustomerModel;
import de.hybris.platform.b2b.model.B2BUnitModel;
import de.hybris.platform.core.model.user.UserModel;
import de.hybris.platform.servicelayer.user.UserService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.springframework.http.ResponseEntity;

/**
 * Who the caller is for the self-service endpoints: the signed-in B2B customer, the customer number
 * of the company (the default B2B unit's uid = the S/4 sold-to party) and the Sales and Service Cloud
 * account that carries this number as an external ID. Everything a customer sees is limited to one
 * of the two.
 */
public class CustomerContext
{
	private static final Pattern CUSTOMER_NUMBER = Pattern.compile("[0-9A-Za-z]{1,10}");
	private static final long CACHE_MILLIS = 10 * 60 * 1000L;

	private final Map<String, Cached> cache = new ConcurrentHashMap<>();

	private UserService userService;
	private SscCaseClient sscClient;

	private static final class Cached
	{
		private final Object value;
		private final long until;

		Cached(final Object value)
		{
			this.value = value;
			this.until = System.currentTimeMillis() + CACHE_MILLIS;
		}
	}

	/** Customer number of the caller's company; 403 for anyone who is not a B2B customer with a unit. */
	public String customerNumber()
	{
		final UserModel user = userService.getCurrentUser();
		if (!(user instanceof B2BCustomerModel))
		{
			throw new SscException(403, "This is available to business customers only");
		}
		final B2BUnitModel unit = ((B2BCustomerModel) user).getDefaultB2BUnit();
		if (unit == null || unit.getUid() == null || !CUSTOMER_NUMBER.matcher(unit.getUid()).matches())
		{
			throw new SscException(403, "Your account has no business unit with a customer number");
		}
		return unit.getUid();
	}

	/** Sales and Service Cloud account (id, displayId, name) of the caller's company. */
	@SuppressWarnings("unchecked")
	public Map<String, String> account()
	{
		final String customerNumber = customerNumber();
		final Object cached = cached("account:" + customerNumber);
		if (cached != null)
		{
			return (Map<String, String>) cached;
		}
		final JsonNode accounts = sscClient.get("account-service/accounts?$top=1&$filter="
				+ SscCaseClient.encode("externalIds/externalId eq '" + customerNumber + "'"));
		if (!accounts.isArray() || accounts.isEmpty())
		{
			throw new SscException(404, "Your company (customer " + customerNumber + ") is not set up in Sales and Service Cloud yet");
		}
		final Map<String, String> account = new LinkedHashMap<>();
		account.put("id", accounts.get(0).path("id").asText());
		account.put("displayId", accounts.get(0).path("displayId").asText());
		account.put("name", accounts.get(0).path("formattedName").asText());
		cache(("account:" + customerNumber), account);
		return account;
	}

	/** "Name (login)" of the caller, on one line: the signature of what a customer writes. */
	public String signature()
	{
		final UserModel user = userService.getCurrentUser();
		final String uid = oneLine(user.getUid()).replaceAll("[()\\s]", "");
		final String name = user.getName() == null || user.getName().isBlank() ? uid : oneLine(user.getName());
		return name + " (" + uid + ")";
	}

	/** A value kept for ten minutes (case types, the processor, accounts). */
	public Object cached(final String key)
	{
		final Cached entry = cache.get(key);
		return entry != null && entry.until > System.currentTimeMillis() ? entry.value : null;
	}

	public void cache(final String key, final Object value)
	{
		cache.put(key, new Cached(value));
	}

	/** The error body the storefront reads: {"errors":[{"type":...,"message":...}]}. */
	public static ResponseEntity<Map<String, Object>> error(final int status, final String type, final String message)
	{
		final Map<String, Object> error = new LinkedHashMap<>();
		error.put("type", type);
		error.put("message", message);
		final List<Map<String, Object>> errors = new ArrayList<>();
		errors.add(error);
		final Map<String, Object> body = new LinkedHashMap<>();
		body.put("errors", errors);
		return ResponseEntity.status(status >= 400 && status < 600 ? status : 502).body(body);
	}

	private static String oneLine(final String value)
	{
		return value == null ? "" : value.replaceAll("\\s+", " ").strip();
	}

	public void setUserService(final UserService userService)
	{
		this.userService = userService;
	}

	public void setSscClient(final SscCaseClient sscClient)
	{
		this.sscClient = sscClient;
	}
}
