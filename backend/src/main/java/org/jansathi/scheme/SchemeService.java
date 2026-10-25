package org.jansathi.scheme;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jansathi.content.BundleRepository;
import org.jansathi.content.ContentBundle;
import org.jansathi.content.Localized;
import org.jansathi.rules.RulesEngine;
import org.jansathi.rules.SchemeResult;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

@Service
public class SchemeService {

	private final BundleRepository bundles;
	private final RulesEngine engine;

	public SchemeService(BundleRepository bundles, RulesEngine engine) {
		this.bundles = bundles;
		this.engine = engine;
	}

	public ContentBundle bundle() {
		return bundles.get();
	}

	/** Trust label and staleness of a scheme as such: an empty profile, so only its sources matter. */
	public SchemeResult trust(JsonNode scheme) {
		return engine.evaluateScheme(scheme, JsonNodeFactory.instance.objectNode(), bundles.get().fields(), LocalDate.now());
	}

	public SchemeSummary summarise(JsonNode scheme, String lang) {
		JsonNode content = Localized.content(scheme, lang);
		SchemeResult trust = trust(scheme);
		return new SchemeSummary(
				scheme.path("slug").asString(),
				scheme.path("category").asString(),
				scheme.path("icon").asString(""),
				scheme.path("level").asString("central"),
				scheme.path("state_code").asString(null),
				scheme.path("status").asString(),
				Localized.contentLang(scheme, lang),
				content.path("name").asString(""),
				content.path("summary").asString(""),
				trust.label(),
				trust.stale());
	}

	/** Central schemes, plus one state's when given, optionally of one category. */
	public List<SchemeSummary> list(String category, String state, String lang) {
		return bundles.get().schemesFor(state).stream()
				.filter(s -> category == null || category.equals(s.path("category").asString()))
				.map(s -> summarise(s, lang))
				.toList();
	}

	/** Category code to number of schemes, in code order. */
	public Map<String, Integer> categories(String state) {
		Map<String, Integer> out = new TreeMap<>();
		bundles.get().schemesFor(state).forEach(s -> out.merge(s.path("category").asString(), 1, Integer::sum));
		return out;
	}
}
