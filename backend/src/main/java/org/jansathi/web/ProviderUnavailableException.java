package org.jansathi.web;

/** Maps to 503. The message is for the log and must not hold a number or a key. */
public class ProviderUnavailableException extends RuntimeException {

	public ProviderUnavailableException(String message) {
		super(message);
	}
}
