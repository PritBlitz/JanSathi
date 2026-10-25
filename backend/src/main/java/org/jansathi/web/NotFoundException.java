package org.jansathi.web;

/** Maps to 404 with a problem+json body. */
public class NotFoundException extends RuntimeException {

	public NotFoundException(String message) {
		super(message);
	}
}
