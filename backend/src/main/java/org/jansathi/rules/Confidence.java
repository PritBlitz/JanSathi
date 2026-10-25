package org.jansathi.rules;

import com.fasterxml.jackson.annotation.JsonValue;

/** How well a rule is supported by its source, from weakest to strongest (§9.5). */
public enum Confidence {
	UNCERTAIN,
	GUIDANCE,
	CONFIRMED;

	@JsonValue
	public String json() {
		return name().toLowerCase();
	}

	/** Parses the content value; anything unrecognised counts as the weakest level. */
	public static Confidence parse(String value) {
		if (value == null) {
			return UNCERTAIN;
		}
		return switch (value) {
			case "confirmed" -> CONFIRMED;
			case "guidance" -> GUIDANCE;
			default -> UNCERTAIN;
		};
	}
}
