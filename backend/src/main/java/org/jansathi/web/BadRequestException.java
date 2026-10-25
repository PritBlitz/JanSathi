package org.jansathi.web;

/** Maps to 400 with a problem+json body. The message never echoes profile values. */
public class BadRequestException extends RuntimeException {

	public BadRequestException(String message) {
		super(message);
	}
}
