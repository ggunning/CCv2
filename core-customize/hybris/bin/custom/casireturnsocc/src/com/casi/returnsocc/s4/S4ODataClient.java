package com.casi.returnsocc.s4;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.hybris.platform.apiregistryservices.model.AbstractCredentialModel;
import de.hybris.platform.apiregistryservices.model.BasicCredentialModel;
import de.hybris.platform.apiregistryservices.model.ConsumedDestinationModel;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Minimal OData V2 client for SAP S/4HANA Cloud, configured entirely through a Commerce
 * ConsumedDestination: URL (service root, e.g. .../API_CUSTOMER_RETURN_SRV/), a Basic credential,
 * and the additional property "csrfURL" (same convention as SAP S/4HANA Order Management).
 * Each call opens its own HTTP client so the CSRF token and its session cookie stay together.
 */
public class S4ODataClient
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private FlexibleSearchService flexibleSearchService;

	/** HTTP error from S/4, with S/4's own messages. */
	public static class S4Exception extends RuntimeException
	{
		private final int status;

		public S4Exception(final int status, final String message)
		{
			super(message);
			this.status = status;
		}

		public int getStatus()
		{
			return status;
		}
	}

	/** GET a path below the destination's service root and return the OData "d" node. */
	public JsonNode get(final String destinationId, final String path)
	{
		return new Connection(destination(destinationId)).send("GET", path, null);
	}

	/** POST a JSON body (with the CSRF handshake) and return the OData "d" node. */
	public JsonNode post(final String destinationId, final String path, final Object body)
	{
		final Connection connection = new Connection(destination(destinationId));
		connection.fetchCsrfToken();
		return connection.send("POST", path, body);
	}

	/** PATCH a JSON body (with the CSRF handshake): changes the named fields of one entity. S/4 answers without a body. */
	public JsonNode patch(final String destinationId, final String path, final Object body)
	{
		final Connection connection = new Connection(destination(destinationId));
		connection.fetchCsrfToken();
		return connection.send("PATCH", path, body);
	}

	private ConsumedDestinationModel destination(final String id)
	{
		// Only active ones: SAP S/4HANA Order Management ships inactive sample destinations with the same ids
		// (billingDestination, s4omOrderDestination, ...), and the first match would be the sample.
		final FlexibleSearchQuery query = new FlexibleSearchQuery(
				"SELECT {pk} FROM {ConsumedDestination} WHERE {id} = ?id AND {active} = ?active ORDER BY {pk}");
		query.addQueryParameter("active", Boolean.TRUE);
		query.addQueryParameter("id", id);
		final List<ConsumedDestinationModel> result = flexibleSearchService.<ConsumedDestinationModel> search(query).getResult();
		if (result.isEmpty())
		{
			throw new S4Exception(500, "No active consumed destination '" + id + "'");
		}
		return result.get(0);
	}

	private final class Connection
	{
		private final HttpClient http;
		private final String serviceRoot;
		private final String authorization;
		private final String csrfUrl;
		private String csrfToken;

		Connection(final ConsumedDestinationModel destination)
		{
			final String url = destination.getUrl();
			this.serviceRoot = url.endsWith("/") ? url : url + "/";
			final AbstractCredentialModel credential = destination.getCredential();
			if (!(credential instanceof BasicCredentialModel))
			{
				throw new S4Exception(500, "Destination '" + destination.getId() + "' needs a Basic credential");
			}
			final BasicCredentialModel basic = (BasicCredentialModel) credential;
			final String userAndPassword = basic.getUsername() + ":" + basic.getPassword();
			this.authorization = "Basic " + Base64.getEncoder().encodeToString(userAndPassword.getBytes(StandardCharsets.UTF_8));
			final Map<String, String> properties = destination.getAdditionalProperties();
			this.csrfUrl = properties != null && properties.get("csrfURL") != null ? properties.get("csrfURL") : serviceRoot;
			this.http = HttpClient.newBuilder().cookieHandler(new CookieManager()).connectTimeout(Duration.ofSeconds(30)).build();
		}

		void fetchCsrfToken()
		{
			final HttpRequest request = HttpRequest.newBuilder(URI.create(csrfUrl)).timeout(Duration.ofSeconds(60))
					.header("Authorization", authorization).header("x-csrf-token", "fetch").GET().build();
			final HttpResponse<String> response = execute(request);
			if (response.statusCode() >= 400)
			{
				throw new S4Exception(response.statusCode(), "CSRF token fetch failed: " + errorMessage(response.body()));
			}
			csrfToken = response.headers().firstValue("x-csrf-token").orElse(null);
		}

		JsonNode send(final String method, final String path, final Object body)
		{
			final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(serviceRoot + path))
					.timeout(Duration.ofSeconds(60)).header("Authorization", authorization).header("Accept", "application/json");
			if (body == null)
			{
				builder.GET();
			}
			else
			{
				builder.header("Content-Type", "application/json");
				if (csrfToken != null)
				{
					builder.header("x-csrf-token", csrfToken);
				}
				builder.method(method, HttpRequest.BodyPublishers.ofString(toJson(body), StandardCharsets.UTF_8));
			}
			final HttpResponse<String> response = execute(builder.build());
			if (response.statusCode() >= 400)
			{
				throw new S4Exception(response.statusCode(), errorMessage(response.body()));
			}
			final String text = response.body();
			if (text == null || text.isBlank())
			{
				return MAPPER.createObjectNode();
			}
			try
			{
				return MAPPER.readTree(text).path("d");
			}
			catch (final IOException e)
			{
				throw new S4Exception(502, "Unreadable S/4 response: " + e.getMessage());
			}
		}

		private HttpResponse<String> execute(final HttpRequest request)
		{
			try
			{
				return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			}
			catch (final IOException e)
			{
				throw new S4Exception(502, "S/4 not reachable: " + e.getMessage());
			}
			catch (final InterruptedException e)
			{
				Thread.currentThread().interrupt();
				throw new S4Exception(503, "Interrupted calling S/4");
			}
		}
	}

	private static String toJson(final Object body)
	{
		try
		{
			return MAPPER.writeValueAsString(body);
		}
		catch (final IOException e)
		{
			throw new S4Exception(500, "Cannot serialise request: " + e.getMessage());
		}
	}

	/** S/4 OData error text: the main message plus the non-framework detail messages. */
	static String errorMessage(final String body)
	{
		try
		{
			final JsonNode error = MAPPER.readTree(body).path("error");
			final StringBuilder text = new StringBuilder(error.path("message").path("value").asText(""));
			for (final JsonNode detail : error.path("innererror").path("errordetails"))
			{
				final String message = detail.path("message").asText("");
				if (!message.isEmpty() && !detail.path("code").asText("").startsWith("/IWBEP/") && text.indexOf(message) < 0)
				{
					text.append(" | ").append(message);
				}
			}
			return text.length() > 0 ? text.toString() : body;
		}
		catch (final IOException | RuntimeException e)
		{
			return body == null ? "" : body.substring(0, Math.min(500, body.length()));
		}
	}

	public void setFlexibleSearchService(final FlexibleSearchService flexibleSearchService)
	{
		this.flexibleSearchService = flexibleSearchService;
	}
}
