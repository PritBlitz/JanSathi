package org.jansathi.scheme;

import org.jansathi.rules.Confidence;

/**
 * A scheme in a list. {@code lang} is the language {@code name} and {@code summary} are actually
 * in (English when no translation exists). {@code label} and {@code stale} are computed by the
 * rules engine from the scheme's sources, never from a profile.
 */
public record SchemeSummary(
		String slug,
		String category,
		String icon,
		String level,
		String stateCode,
		String status,
		String lang,
		String name,
		String summary,
		Confidence label,
		boolean stale) {}
