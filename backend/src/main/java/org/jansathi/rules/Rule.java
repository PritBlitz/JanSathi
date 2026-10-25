package org.jansathi.rules;

import java.util.Objects;

/**
 * One eligibility condition of a scheme.
 *
 * @param code stable id within the scheme, e.g. {@code age_min}
 * @param jsonLogic the condition as a json-logic JSON string
 * @param hard a failing hard rule makes the scheme "not eligible"; soft rules never change the result
 * @param confidence how well the source supports the rule
 */
public record Rule(String code, String jsonLogic, boolean hard, Confidence confidence) {

	public Rule {
		Objects.requireNonNull(code, "code");
		Objects.requireNonNull(jsonLogic, "jsonLogic");
		Objects.requireNonNull(confidence, "confidence");
	}
}
