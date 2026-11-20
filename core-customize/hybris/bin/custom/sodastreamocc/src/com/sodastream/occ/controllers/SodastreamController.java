package com.sodastream.occ.controllers;

import com.sodastream.occ.service.SodastreamService;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;


/**
 * Public, read-only endpoints for {@link SodastreamService}. Nothing here changes data.
 *
 * GET /occ/v2/{baseSiteId}/sodastream/status          JSON status
 * GET /occ/v2/{baseSiteId}/sodastream/fizz?message=   JSON echo
 * GET /occ/v2/{baseSiteId}/sodastream/test            HTML test page (server-rendered, no JavaScript)
 */
@Controller
@RequestMapping(value = "/{baseSiteId}/sodastream")
public class SodastreamController
{
	private SodastreamService sodastreamService;

	@GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
	@ResponseBody
	public ResponseEntity<Map<String, Object>> status(@PathVariable("baseSiteId") final String baseSiteId)
	{
		final Map<String, Object> body = new LinkedHashMap<>();
		body.put("baseSiteId", baseSiteId);
		body.putAll(sodastreamService.status());
		return ResponseEntity.ok(body);
	}

	@GetMapping(value = "/fizz", produces = MediaType.APPLICATION_JSON_VALUE)
	@ResponseBody
	public ResponseEntity<Map<String, Object>> fizz(@PathVariable("baseSiteId") final String baseSiteId,
			@RequestParam(value = "message", required = false) final String message)
	{
		final Map<String, Object> body = new LinkedHashMap<>();
		body.put("baseSiteId", baseSiteId);
		body.put("message", message);
		body.put("result", sodastreamService.fizz(message));
		return ResponseEntity.ok(body);
	}

	@GetMapping(value = "/test", produces = MediaType.TEXT_HTML_VALUE)
	@ResponseBody
	public ResponseEntity<String> testPage(@PathVariable("baseSiteId") final String baseSiteId,
			@RequestParam(value = "message", required = false) final String message)
	{
		final StringBuilder html = new StringBuilder(2048);
		html.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
				.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
				.append("<title>Sodastream test</title><style>")
				.append("body{font-family:system-ui,sans-serif;max-width:720px;margin:2rem auto;padding:0 1rem;color:#1b1b1b}")
				.append("table{border-collapse:collapse;width:100%}td{border-bottom:1px solid #ddd;padding:.4rem .6rem;vertical-align:top}")
				.append("td:first-child{color:#555;width:30%}.ok{color:#0a7d32;font-weight:600}")
				.append("input{padding:.4rem;width:60%}button{padding:.4rem .9rem}</style></head><body>")
				.append("<h1>Sodastream extension test</h1>");
		try
		{
			final Map<String, Object> status = sodastreamService.status();
			html.append("<p class=\"ok\">PASS: SodastreamService answered on site ").append(escape(baseSiteId)).append("</p>");
			html.append("<h2>status()</h2><table>");
			for (final Map.Entry<String, Object> e : status.entrySet())
			{
				html.append("<tr><td>").append(escape(e.getKey())).append("</td><td>").append(escape(String.valueOf(e.getValue())))
						.append("</td></tr>");
			}
			html.append("</table>");
			html.append("<h2>fizz(message)</h2><form method=\"get\">")
					.append("<input name=\"message\" maxlength=\"200\" value=\"").append(escape(message == null ? "" : message))
					.append("\" placeholder=\"type something\"> <button type=\"submit\">Send</button></form>");
			if (message != null)
			{
				html.append("<p>Result: <strong>").append(escape(sodastreamService.fizz(message))).append("</strong></p>");
			}
		}
		catch (final RuntimeException e)
		{
			html.append("<p style=\"color:#b00020;font-weight:600\">FAIL: ").append(escape(e.getClass().getSimpleName())).append(": ")
					.append(escape(String.valueOf(e.getMessage()))).append("</p>");
		}
		html.append("<p style=\"color:#555\">JSON: <code>status</code> and <code>fizz?message=</code> next to this page.</p>")
				.append("</body></html>");
		return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html.toString());
	}

	private static String escape(final String text)
	{
		final StringBuilder out = new StringBuilder(text.length() + 16);
		for (int i = 0; i < text.length(); i++)
		{
			final char c = text.charAt(i);
			switch (c)
			{
				case '<':
					out.append("&lt;");
					break;
				case '>':
					out.append("&gt;");
					break;
				case '&':
					out.append("&amp;");
					break;
				case '"':
					out.append("&quot;");
					break;
				case '\'':
					out.append("&#39;");
					break;
				default:
					out.append(c);
			}
		}
		return out.toString();
	}

	public void setSodastreamService(final SodastreamService sodastreamService)
	{
		this.sodastreamService = sodastreamService;
	}
}
