package org.jansathi.rules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Profile fields from {@code content/fields.json}: their type and their order. The order breaks
 * ties everywhere in §9 (corner enumeration, missing fields, next question).
 */
public final class FieldCatalog {

	private final Map<String, Integer> order = new HashMap<>();
	private final Map<String, String> types = new HashMap<>();

	/** @param fieldsJson the whole fields.json document ({@code {core, fields}}) */
	public FieldCatalog(JsonNode fieldsJson) {
		int i = 0;
		for (Map.Entry<String, JsonNode> e : fieldsJson.path("fields").properties()) {
			order.put(e.getKey(), i++);
			types.put(e.getKey(), e.getValue().path("type").asString(""));
		}
	}

	public boolean isBand(String field) {
		return "band".equals(types.get(field));
	}

	public boolean isKnown(String field) {
		return types.containsKey(field);
	}

	/** De-duplicates and sorts by fields.json order; unknown names go last, alphabetically. */
	public List<String> sorted(Collection<String> names) {
		List<String> list = new ArrayList<>(new LinkedHashSet<>(names));
		list.sort(Comparator.<String>comparingInt(n -> order.getOrDefault(n, Integer.MAX_VALUE))
				.thenComparing(Comparator.naturalOrder()));
		return list;
	}
}
