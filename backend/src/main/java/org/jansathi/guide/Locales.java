package org.jansathi.guide;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jansathi.content.Languages;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The PWA's UI strings (packaged from frontend/public/locales), so the guide asks each question
 * with exactly the wording people see in the app. Missing keys fall back to English, then the key.
 */
@Component
public class Locales {

	private final ObjectMapper json;
	private final Map<String, JsonNode> cache = new ConcurrentHashMap<>();

	public Locales(ObjectMapper json) {
		this.json = json;
	}

	private JsonNode load(String lang) {
		return cache.computeIfAbsent(lang, l -> {
			try (InputStream in = new ClassPathResource("locales/" + l + ".json").getInputStream()) {
				return json.readTree(in);
			} catch (IOException e) {
				return json.createObjectNode();
			}
		});
	}

	public String t(String lang, String key) {
		JsonNode v = load(Languages.orEnglish(lang)).path(key);
		if (v.isString()) {
			return v.asString();
		}
		JsonNode en = load("en").path(key);
		return en.isString() ? en.asString() : key;
	}

	public boolean has(String key) {
		return load("en").has(key);
	}
}
