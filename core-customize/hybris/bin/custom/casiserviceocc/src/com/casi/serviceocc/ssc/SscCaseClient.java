package com.casi.serviceocc.ssc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.hybris.platform.apiregistryservices.model.AbstractCredentialModel;
import de.hybris.platform.apiregistryservices.model.BasicCredentialModel;
import de.hybris.platform.apiregistryservices.model.ConsumedDestinationModel;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import de.hybris.platform.util.Config;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Minimal REST client for SAP Sales and Service Cloud Version 2 (https://&lt;tenant&gt;/sap/c4c/api/v1/),
 * configured through a Commerce ConsumedDestination: URL (the API root) and a Basic credential of a
 * Sales and Service Cloud API user. Additional properties of the destination override the settings
 * in project.properties (casiserviceocc.&lt;name&gt;).
 */
public class SscCaseClient
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String DESTINATION_PROPERTY = "casiserviceocc.destination";
	private static final String DEFAULT_DESTINATION = "casiSscCaseDestination";

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

	private FlexibleSearchService flexibleSearchService;

	/** Error from Sales and Service Cloud or from the set-up, with the HTTP status the storefront gets. */
	public static class SscException extends RuntimeException
	{
		private final int status;

		public SscException(final int status, final String message)
		{
			super(message);
			this.status = status;
		}

		public int getStatus()
		{
			return status;
		}
	}

	/** A response body together with the entity's ETag. */
	public static class Entity
	{
		private final JsonNode value;
		private final String etag;

		Entity(final JsonNode value, final String etag)
		{
			this.value = value;
			this.etag = etag;
		}

		public JsonNode getValue()
		{
			return value;
		}

		public String getEtag()
		{
			return etag;
		}
	}

	/** GET a path below the API root and return the "value" node (an object or an array). */
	public JsonNode get(final String path)
	{
		return send("GET", path, null, null).getValue();
	}

	/** GET one entity with its ETag. */
	public Entity getEntity(final String path)
	{
		return send("GET", path, null, null);
	}

	/** POST a JSON body and return the "value" node. */
	public JsonNode post(final String path, final Object body)
	{
		return send("POST", path, body, null).getValue();
	}

	/** Merge-patch an entity (the API needs its current ETag) and return the "value" node. */
	public JsonNode patch(final String path, final Object body, final String etag)
	{
		return send("PATCH", path, body, etag).getValue();
	}

	/**
	 * A setting: the destination's additional property "name" when filled in, otherwise the
	 * property casiserviceocc.name, otherwise the fallback.
	 */
	public String setting(final String name, final String fallback)
	{
		final Map<String, String> properties = destination().getAdditionalProperties();
		final String own = properties == null ? null : properties.get(name);
		if (own != null && !own.isBlank())
		{
			return own.trim();
		}
		final String configured = Config.getString("casiserviceocc." + name, fallback);
		return configured == null || configured.isBlank() ? fallback : configured.trim();
	}

	/** Percent-encode a query value (a $filter expression, for example). */
	public static String encode(final String value)
	{
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
	}

	private Entity send(final String method, final String path, final Object body, final String etag)
	{
		final ConsumedDestinationModel destination = destination();
		final String url = destination.getUrl();
		final String root = url.endsWith("/") ? url : url + "/";
		final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(root + path)).timeout(Duration.ofSeconds(60))
				.header("Authorization", authorization(destination)).header("Accept", "application/json");
		if (body == null)
		{
			builder.GET();
		}
		else
		{
			builder.header("Content-Type", "PATCH".equals(method) ? "application/merge-patch+json" : "application/json");
			if (etag != null)
			{
				builder.header("If-Match", etag);
			}
			builder.method(method, HttpRequest.BodyPublishers.ofString(toJson(body), StandardCharsets.UTF_8));
		}
		final HttpResponse<String> response = execute(builder.build());
		if (response.statusCode() >= 400)
		{
			// 400/404/422 are about the request; anything else (also 401/403: that is our API user,
			// not the customer's session) is a failed call to the other system.
			final int code = response.statusCode();
			throw new SscException(code == 404 ? 404 : code == 400 || code == 422 ? 400 : 502, errorMessage(response.body()));
		}
		final String text = response.body();
		if (text == null || text.isBlank())
		{
			return new Entity(MAPPER.createObjectNode(), null);
		}
		try
		{
			return new Entity(MAPPER.readTree(text).path("value"), response.headers().firstValue("ETag").orElse(null));
		}
		catch (final IOException e)
		{
			throw new SscException(502, "Unreadable response from Sales and Service Cloud: " + e.getMessage());
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
			throw new SscException(502, "Sales and Service Cloud is not reachable: " + e.getMessage());
		}
		catch (final InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new SscException(503, "Interrupted calling Sales and Service Cloud");
		}
	}

	private ConsumedDestinationModel destination()
	{
		final String id = Config.getString(DESTINATION_PROPERTY, DEFAULT_DESTINATION);
		final FlexibleSearchQuery query = new FlexibleSearchQuery("SELECT {pk} FROM {ConsumedDestination} WHERE {id} = ?id");
		query.addQueryParameter("id", id);
		final List<ConsumedDestinationModel> result = flexibleSearchService.<ConsumedDestinationModel> search(query).getResult();
		if (result.isEmpty())
		{
			throw new SscException(500, "Consumed destination '" + id + "' is not configured");
		}
		return result.get(0);
	}

	private static String authorization(final ConsumedDestinationModel destination)
	{
		final AbstractCredentialModel credential = destination.getCredential();
		if (!(credential instanceof BasicCredentialModel))
		{
			throw new SscException(500, "Destination '" + destination.getId() + "' needs a Basic credential");
		}
		final BasicCredentialModel basic = (BasicCredentialModel) credential;
		final String userAndPassword = basic.getUsername() + ":" + basic.getPassword();
		return "Basic " + Base64.getEncoder().encodeToString(userAndPassword.getBytes(StandardCharsets.UTF_8));
	}

	private static String toJson(final Object body)
	{
		try
		{
			return MAPPER.writeValueAsString(body);
		}
		catch (final IOException e)
		{
			throw new SscException(500, "Cannot serialise request: " + e.getMessage());
		}
	}

	/** Error text of the API: the main message plus the detail messages. */
	static String errorMessage(final String body)
	{
		try
		{
			final JsonNode error = MAPPER.readTree(body).path("error");
			final StringBuilder text = new StringBuilder(error.path("message").asText(""));
			for (final JsonNode detail : error.path("details"))
			{
				final String message = detail.path("message").asText("");
				if (!message.isEmpty() && text.indexOf(message) < 0)
				{
					text.append(text.length() > 0 ? " | " : "").append(message);
				}
			}
			return text.length() > 0 ? text.toString() : shorten(body);
		}
		catch (final IOException | RuntimeException e)
		{
			return shorten(body);
		}
	}

	private static String shorten(final String body)
	{
		return body == null ? "" : body.substring(0, Math.min(300, body.length()));
	}

	public void setFlexibleSearchService(final FlexibleSearchService flexibleSearchService)
	{
		this.flexibleSearchService = flexibleSearchService;
	}
}
