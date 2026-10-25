package org.jansathi.rules;

import java.util.List;

/**
 * The result for one scheme, with the per-rule trace used for explanations and read-aloud.
 *
 * @param missingFields fields still needed by unknown hard rules, in rule order, without duplicates
 */
public record SchemeEvaluation(MatchResult result, List<RuleTrace> rules, List<String> missingFields) {

	public SchemeEvaluation {
		rules = List.copyOf(rules);
		missingFields = List.copyOf(missingFields);
	}
}
