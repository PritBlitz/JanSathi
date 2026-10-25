package org.jansathi.assist;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jansathi.content.BundleRepository;
import org.jansathi.content.ContentBundle;
import org.jansathi.content.Languages;
import org.jansathi.content.Localized;
import org.jansathi.rules.Confidence;
import org.jansathi.search.TextMatch;
import org.jansathi.web.NotFoundException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Retrieval for the assistant (docs/RAG_ASSISTANT.md step 3). Passages are scheme rules, each tied
 * to an official source and the clause it comes from, so every passage can be cited. Keyword
 * scoring with inverse document frequency until pgvector embeddings exist.
 */
@Component
public class Retriever {

	/** Runs of 9 or more digits (allowing spaces and hyphens): Aadhaar, bank account, phone numbers. */
	private static final Pattern PERSONAL_NUMBER = Pattern.compile("\\d(?:[\\s-]?\\d){8,}");
	private static final int TOP_K = 6;
	/** Diversity: one scheme cannot fill the answer (§2 allows 3 per source; schemes are smaller). */
	private static final int MAX_PER_SCHEME = 2;
	/**
	 * The "no official source" gate: at least this share of the question's keywords must appear
	 * somewhere in the official passages, so "a good biryani recipe" does not match "sells goods".
	 */
	static final double MIN_COVERAGE = 0.5;
	private static final int DEFAULT_REVERIFY_DAYS = 180;

	private final BundleRepository bundles;

	public Retriever(BundleRepository bundles) {
		this.bundles = bundles;
	}

	/** One citable passage: a rule's plain-language text, its source and clause. */
	public record Passage(
			String id,
			String scheme,
			Localized schemeName,
			Localized text,
			Confidence confidence,
			String locator,
			JsonNode source,
			boolean stale) {}

	private record Candidate(Passage passage, double score) {}

	/** Removes personal numbers before the question is used for anything. */
	static String redact(String question) {
		return PERSONAL_NUMBER.matcher(question).replaceAll(" ");
	}

	/** The best passages for a question, at most 3 per scheme; empty when nothing matches. */
	public List<Passage> retrieve(String question, String lang, String schemeSlug, String state) {
		ContentBundle b = bundles.get();
		String l = Languages.orEnglish(lang);
		List<String> words = TextMatch.keywords(redact(question));

		List<JsonNode> schemes = schemeSlug == null
				? b.schemesFor(state)
				: List.of(b.scheme(schemeSlug).orElseThrow(() -> new NotFoundException("No scheme with slug " + schemeSlug)));

		List<Candidate> candidates = new ArrayList<>();
		List<Set<String>> texts = new ArrayList<>();
		for (JsonNode s : schemes) {
			Map<String, JsonNode> sources = new HashMap<>();
			s.path("sources").forEach(src -> sources.put(src.path("id").asString(""), src));
			Localized name = Localized.pick(namesOf(s), l);
			for (JsonNode rule : s.path("rules")) {
				JsonNode src = sources.get(rule.path("source").asString(""));
				if (src == null) {
					continue; // never cite a passage without its source
				}
				Localized text = Localized.pick(rule.path("text"), l);
				String hay = TextMatch.normalise(String.join(" ",
						text.text(), rule.path("text").path("en").asString(""), name.text(),
						s.path("content").path("en").path("name").asString(""), s.path("category").asString("")));
				texts.add(TextMatch.tokens(hay));
				Passage p = new Passage(
						"", s.path("slug").asString(), name, text,
						Confidence.parse(rule.path("confidence").asString(null)),
						rule.path("locator").asString(null), src, isStale(s, src));
				candidates.add(new Candidate(p, 0));
			}
		}

		// Inverse document frequency, so rare words (a scheme name) outweigh common ones.
		Map<String, Double> idf = new HashMap<>();
		for (String w : words) {
			long df = texts.stream().filter(t -> TextMatch.matches(w, t)).count();
			idf.put(w, df == 0 ? 0 : Math.log(1 + (double) texts.size() / df));
		}
		long known = words.stream().filter(w -> idf.get(w) > 0).count();
		if (words.isEmpty() || (double) known / words.size() < MIN_COVERAGE) {
			return List.of();
		}
		List<Candidate> scored = new ArrayList<>();
		for (int i = 0; i < candidates.size(); i++) {
			Set<String> hay = texts.get(i);
			double score = words.stream().filter(w -> TextMatch.matches(w, hay)).mapToDouble(idf::get).sum();
			if (score > 0) {
				scored.add(new Candidate(candidates.get(i).passage(), score));
			}
		}
		scored.sort(Comparator.comparingDouble(Candidate::score).reversed());

		List<Passage> passages = new ArrayList<>();
		Map<String, Integer> perScheme = new HashMap<>();
		for (Candidate c : scored) {
			if (passages.size() == TOP_K) {
				break;
			}
			if (perScheme.merge(c.passage().scheme(), 1, Integer::sum) > MAX_PER_SCHEME) {
				continue;
			}
			Passage p = c.passage();
			passages.add(new Passage("p" + (passages.size() + 1), p.scheme(), p.schemeName(), p.text(), p.confidence(), p.locator(), p.source(), p.stale()));
		}

		return passages;
	}

	/** The scheme's name in every language it has, as a {@code {en, hi, …}} object. */
	private static JsonNode namesOf(JsonNode scheme) {
		ObjectNode names = JsonNodeFactory.instance.objectNode();
		scheme.path("content").properties().forEach(e -> names.put(e.getKey(), e.getValue().path("name").asString("")));
		return names;
	}

	/** Same staleness test as the rules engine (§9.5), for one source. */
	private static boolean isStale(JsonNode scheme, JsonNode source) {
		if (!"published".equals(scheme.path("status").asString(""))) {
			return true;
		}
		String verifiedAt = source.path("verified_at").asString(null);
		if (verifiedAt == null || verifiedAt.isEmpty()) {
			return true;
		}
		int reverify = scheme.path("reverify_days").isNumber() ? scheme.get("reverify_days").intValue() : DEFAULT_REVERIFY_DAYS;
		return ChronoUnit.DAYS.between(LocalDate.parse(verifiedAt.substring(0, 10)), LocalDate.now()) > reverify;
	}
}
