package org.jansathi.rules;

import java.util.List;

/**
 * The result for one scheme (§9.4–9.5), in the shape of one entry of {@code POST /api/v1/match}.
 *
 * @param label the weakest confidence among the hard rules; always uncertain when stale
 * @param missingFields fields still needed by unknown hard rules, in rule order, without duplicates
 */
public record SchemeResult(
		String scheme, MatchResult result, Confidence label, boolean stale, List<String> missingFields, List<RuleTrace> rules) {

	public SchemeResult {
		missingFields = List.copyOf(missingFields);
		rules = List.copyOf(rules);
	}
}
