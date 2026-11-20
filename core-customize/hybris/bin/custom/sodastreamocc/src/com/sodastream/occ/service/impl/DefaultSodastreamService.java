package com.sodastream.occ.service.impl;

import com.sodastream.occ.service.SodastreamService;
import de.hybris.platform.basecommerce.model.site.BaseSiteModel;
import de.hybris.platform.core.Registry;
import de.hybris.platform.site.BaseSiteService;
import de.hybris.platform.util.Config;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;


/**
 * Default {@link SodastreamService}: reads its greeting from {@code sodastreamocc.greeting} and uses
 * {@link BaseSiteService} so a working status also proves service-layer access.
 */
public class DefaultSodastreamService implements SodastreamService
{
	private static final int MAX_MESSAGE = 200;

	private BaseSiteService baseSiteService;

	@Override
	public Map<String, Object> status()
	{
		final Map<String, Object> status = new LinkedHashMap<>();
		status.put("extension", "sodastreamocc");
		status.put("greeting", Config.getString("sodastreamocc.greeting", "Sodastream extension is wired"));
		status.put("implementation", getClass().getName());
		status.put("clusterNode", Integer.valueOf(Registry.getClusterID()));
		status.put("host", hostName());
		status.put("serverTime", Instant.now().toString());
		final Collection<BaseSiteModel> sites = baseSiteService.getAllBaseSites();
		status.put("baseSites", Integer.valueOf(sites == null ? 0 : sites.size()));
		return status;
	}

	@Override
	public String fizz(final String message)
	{
		String text = message == null ? "" : message.trim();
		if (text.isEmpty())
		{
			text = "hello";
		}
		if (text.length() > MAX_MESSAGE)
		{
			text = text.substring(0, MAX_MESSAGE);
		}
		return text + " ... fizz!";
	}

	private static String hostName()
	{
		try
		{
			return InetAddress.getLocalHost().getHostName();
		}
		catch (final Exception e)
		{
			return "unknown";
		}
	}

	public void setBaseSiteService(final BaseSiteService baseSiteService)
	{
		this.baseSiteService = baseSiteService;
	}
}
