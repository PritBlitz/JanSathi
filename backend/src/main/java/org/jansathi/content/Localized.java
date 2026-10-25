package org.jansathi.content;

import tools.jackson.databind.JsonNode;

/**
 * Text picked for a language from a {@code {en, hi, …}} object, with the language it is actually
 * in. Content falls back to English; the client marks fallback text with {@code lang="en"}.
 */
public record Localized(String text, String lang) {

	public static Localized pick(JsonNode texts, String lang) {
		if (texts == null || !texts.isObject()) {
			return new Localized("", "en");
		}
		if (lang != null && texts.path(lang).isString()) {
			return new Localized(texts.get(lang).asString(), lang);
		}
		return new Localized(texts.path("en").asString(""), "en");
	}

	/** The scheme's {@code content.<lang>} block, or the English one. */
	public static JsonNode content(JsonNode scheme, String lang) {
		JsonNode content = scheme.path("content");
		return lang != null && content.has(lang) ? content.get(lang) : content.path("en");
	}

	public static String contentLang(JsonNode scheme, String lang) {
		return lang != null && scheme.path("content").has(lang) ? lang : "en";
	}
}
