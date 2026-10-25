package org.jansathi.content;

import java.util.Set;

/** The 10 UI languages (docs/I18N_AND_VOICE.md). Anything else is served in English. */
public final class Languages {

	public static final Set<String> CODES = Set.of("en", "hi", "bn", "ta", "te", "mr", "gu", "kn", "ml", "pa");

	private Languages() {}

	public static String orEnglish(String lang) {
		return lang != null && CODES.contains(lang) ? lang : "en";
	}
}
