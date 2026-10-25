package org.jansathi.rules;

import java.util.List;

/**
 * The outcome of one rule for one profile.
 *
 * @param missingFields fields that must be answered (or narrowed, for a band that straddles the
 *     rule's limit) before the rule can pass or fail; empty unless the outcome is unknown
 */
public record RuleTrace(
		String code, RuleOutcome outcome, boolean hard, Confidence confidence, String source, List<String> missingFields) {

	public RuleTrace {
		missingFields = List.copyOf(missingFields);
	}
}
