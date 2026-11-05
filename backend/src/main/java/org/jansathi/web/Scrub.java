package org.jansathi.web;

import java.util.regex.Pattern;

/**
 * Removes personal numbers and secrets from text that leaves the request: logs and error reports
 * (docs/PRIVACY_AND_SECURITY.md §5). Stored text uses the same rule for digit runs.
 */
public final class Scrub {

	/** Runs of 9 or more digits, allowing spaces and hyphens: Aadhaar, bank account, phone numbers. */
	private static final Pattern PERSONAL_NUMBER = Pattern.compile("\\d(?:[\\s-]?\\d){8,}");
	private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+");
	private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[\\w.~+/=-]+");

	private Scrub() {}

	/** For text a person typed that we keep: digit runs go, the rest stays. */
	public static String numbers(String text) {
		return text == null ? null : PERSONAL_NUMBER.matcher(text).replaceAll("[number removed]");
	}

	/** For logs and error reports: also e-mail addresses and bearer tokens. */
	public static String clean(String text) {
		if (text == null) {
			return null;
		}
		return numbers(BEARER.matcher(EMAIL.matcher(text).replaceAll("[email removed]")).replaceAll("[token removed]"));
	}
}
