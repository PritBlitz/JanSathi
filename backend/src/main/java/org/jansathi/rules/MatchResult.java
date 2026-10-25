package org.jansathi.rules;

/**
 * The three possible results for a scheme. User-facing labels never say "you are eligible"; see
 * docs/RULES_ENGINE.md §3.3.
 */
public enum MatchResult {
	LIKELY_ELIGIBLE,
	NEED_MORE_INFO,
	NOT_ELIGIBLE
}
