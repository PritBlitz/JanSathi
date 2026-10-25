package org.jansathi.rules;

import com.fasterxml.jackson.annotation.JsonValue;

/** The outcome of one rule for one profile (docs/RULES_ENGINE.md §9.3). */
public enum RuleOutcome {
	PASS,
	FAIL,
	UNKNOWN;

	@JsonValue
	public String json() {
		return name().toLowerCase();
	}
}
