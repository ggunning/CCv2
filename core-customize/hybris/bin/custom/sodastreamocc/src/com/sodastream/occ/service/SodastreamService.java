package com.sodastream.occ.service;

import java.util.Map;


/**
 * Minimal service contract used to check that a custom extension is built, deployed and wired
 * into both the core and the OCC web contexts.
 */
public interface SodastreamService
{
	/**
	 * Snapshot proving the service runs on the node that answered: configured greeting, cluster node,
	 * host name, server time and how many base sites the service layer can see.
	 *
	 * @return ordered key/value pairs, safe to render as JSON or HTML
	 */
	Map<String, Object> status();

	/**
	 * Echoes a message back with "fizz" added, so a round trip with caller input can be checked.
	 *
	 * @param message
	 *           text from the caller; null or blank becomes "hello"
	 * @return the carbonated message, at most 200 characters of input
	 */
	String fizz(String message);
}
