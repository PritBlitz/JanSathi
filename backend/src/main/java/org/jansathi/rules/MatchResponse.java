package org.jansathi.rules;

import java.util.List;

/** Response of {@code POST /api/v1/match} (§9.7). {@code nextQuestion} is null when nothing is left to ask. */
public record MatchResponse(String rulesetVersion, String nextQuestion, List<SchemeResult> results) {

	public MatchResponse {
		results = List.copyOf(results);
	}
}
