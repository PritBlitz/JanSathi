package org.jansathi.web;

import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Structured logs (LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs) keep no personal numbers: every string
 * passes through {@link Scrub}, stack traces and third-party messages included. Our own code logs no
 * personal data in the first place; this is the net under it.
 */
public class LogScrubber implements StructuredLoggingJsonMembersCustomizer<Object> {

	@Override
	public void customize(JsonWriter.Members<Object> members) {
		members.applyingValueProcessor(JsonWriter.ValueProcessor.of(String.class, Scrub::clean));
	}
}
