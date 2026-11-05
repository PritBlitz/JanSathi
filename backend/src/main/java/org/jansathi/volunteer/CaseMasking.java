package org.jansathi.volunteer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * What of a household a helper may see (brief §6.6, docs/PRIVACY_AND_SECURITY.md §4.3). Everything
 * a helper or the AI working for a helper reads goes through {@link #visible}, so a scope that was
 * not granted cannot leak through results, rule traces, answer history or the guide.
 */
final class CaseMasking {

	/**
	 * Answers that say something a person may not want a helper to know: income, caste and religion,
	 * disability and pregnancy. Hidden unless the person ticks the {@code sensitive} scope. Names are
	 * from content/fields.json; a test keeps them in step.
	 */
	static final List<String> SENSITIVE_FIELDS = List.of(
			"annual_income", "personal_income_over_15000", "member_earns_over_15000", "is_bpl",
			"social_category", "is_minority", "disability", "disability_percent", "is_pregnant_or_lactating");

	private CaseMasking() {}

	/** A copy of the household holding only what these scopes allow. */
	static ObjectNode visible(ObjectNode household, Set<String> scopes) {
		ObjectNode h = household.deepCopy();
		boolean profile = scopes.contains("profile");
		boolean sensitive = scopes.contains("sensitive");
		for (JsonNode node : h.path("members")) {
			ObjectNode m = (ObjectNode) node;
			// History keeps earlier answers, so it never goes to a helper.
			m.putArray("history");
			if (!profile) {
				m.putNull("name");
				m.putObject("profile");
				m.putArray("skipped");
			} else if (!sensitive) {
				ObjectNode answers = JsonNodeFactory.instance.objectNode();
				m.path("profile").properties().forEach(e -> {
					if (!SENSITIVE_FIELDS.contains(e.getKey())) {
						answers.set(e.getKey(), e.getValue());
					}
				});
				ArrayNode skipped = JsonNodeFactory.instance.arrayNode();
				m.path("skipped").forEach(f -> {
					if (!SENSITIVE_FIELDS.contains(f.asString(""))) {
						skipped.add(f);
					}
				});
				m.set("profile", answers);
				m.set("skipped", skipped);
			}
		}
		if (!scopes.contains("checklist")) {
			ObjectNode c = h.putObject("checklist");
			c.putArray("schemes");
			c.putObject("have");
			h.remove("applications");
		}
		return h;
	}

	/**
	 * What the helper is told is hidden: always the whole list, never only what was answered, because
	 * even "this was answered" can say something (a disability percentage implies a disability).
	 */
	static List<String> masked(Set<String> scopes) {
		return scopes.contains("sensitive") ? List.of() : SENSITIVE_FIELDS;
	}

	/** Names of the sensitive answers this household holds, for the audit log. Never the values. */
	static List<String> answered(ObjectNode household) {
		List<String> out = new ArrayList<>();
		for (String field : SENSITIVE_FIELDS) {
			for (JsonNode m : household.path("members")) {
				if (m.path("profile").has(field)) {
					out.add(field);
					break;
				}
			}
		}
		return out;
	}
}
