package org.jansathi.content;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jansathi.rules.FieldCatalog;
import tools.jackson.databind.JsonNode;

/**
 * One loaded rule bundle (docs/RULES_ENGINE.md §9.7): profile fields, document types, central
 * schemes and the schemes of each state. Immutable; a reload builds a new instance.
 *
 * @param raw the bundle.json document as published, served unchanged by {@code GET /rules/bundle}
 * @param preview true when these are draft schemes shown because nothing is published yet
 */
public record ContentBundle(
		String version,
		boolean preview,
		JsonNode raw,
		JsonNode fieldsJson,
		FieldCatalog fields,
		JsonNode documents,
		List<JsonNode> centralSchemes,
		Map<String, List<JsonNode>> stateSchemes) {

	public ContentBundle {
		centralSchemes = List.copyOf(centralSchemes);
		stateSchemes = Map.copyOf(stateSchemes);
	}

	/** Central schemes plus, when a state is given, that state's schemes. */
	public List<JsonNode> schemesFor(String stateCode) {
		if (stateCode == null || !stateSchemes.containsKey(stateCode)) {
			return centralSchemes;
		}
		return java.util.stream.Stream.concat(centralSchemes.stream(), stateSchemes.get(stateCode).stream()).toList();
	}

	/** Every scheme, central first, keyed by slug. */
	public Map<String, JsonNode> allBySlug() {
		Map<String, JsonNode> out = new LinkedHashMap<>();
		centralSchemes.forEach(s -> out.put(s.path("slug").asString(), s));
		stateSchemes.values().forEach(list -> list.forEach(s -> out.put(s.path("slug").asString(), s)));
		return out;
	}

	public Optional<JsonNode> scheme(String slug) {
		return Optional.ofNullable(allBySlug().get(slug));
	}

	public JsonNode document(String code) {
		return documents.path("documents").path(code);
	}
}
