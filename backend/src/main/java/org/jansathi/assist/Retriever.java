package org.jansathi.assist;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.jansathi.content.BundleRepository;
import org.jansathi.content.ContentBundle;
import org.jansathi.content.Languages;
import org.jansathi.content.Localized;
import org.jansathi.provider.embed.Embedder;
import org.jansathi.rules.Confidence;
import org.jansathi.search.TextMatch;
import org.jansathi.web.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Retrieval for the assistant (docs/RAG_ASSISTANT.md step 3). Passages are scheme rules, each tied
 * to an official source and the clause it comes from, so every passage can be cited. Keyword
 * scoring with inverse document frequency, fused with vector similarity when an embedder is on.
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
	/**
	 * And one passage must hold this share itself: as content grew, off-topic questions began to
	 * pass the gate on words scattered across different schemes ("subsidy" here, "new" there).
	 */
	static final double MIN_PASSAGE_COVERAGE = 0.3;
	private static final int DEFAULT_REVERIFY_DAYS = 180;
	/**
	 * Below this cosine similarity a passage is not an answer, and if no passage reaches it the
	 * question gets "no official source" whatever words it shares with the passages. Tuned for
	 * multilingual-e5-small on the dev split of eval/rag; another model needs its own value
	 * (e5 similarities sit high: unrelated text scores about 0.75).
	 */
	static final double MIN_SIMILARITY = 0.83;
	/** After the embedder fails, stay on keywords this long instead of waiting on it every time. */
	private static final Duration EMBED_RETRY = Duration.ofSeconds(30);
	private static final int WARM_UP_ATTEMPTS = 10;
	private static final int RRF_K = 60;
	private static final Logger log = LoggerFactory.getLogger(Retriever.class);

	private final BundleRepository bundles;

	private final Embedder embedder;
	private final double minSimilarity;
	/** Passage vectors by model and text. The texts come from the bundle, so this stays small. */
	private final Map<String, float[]> passageVectors = new ConcurrentHashMap<>();
	private volatile Instant embedderBackAt = Instant.MIN;

	public Retriever(BundleRepository bundles) {
		this(bundles, Embedder.DISABLED);
	}

	@Autowired
	public Retriever(BundleRepository bundles, Embedder embedder) {
		this(bundles, embedder, MIN_SIMILARITY);
	}

	Retriever(BundleRepository bundles, Embedder embedder, double minSimilarity) {
		this.bundles = bundles;
		this.embedder = embedder;
		this.minSimilarity = minSimilarity;
	}

	/**
	 * Embeds the passages at start, off the request path, so the first question is not slowed. The
	 * service may still be starting or loading its model, so failures are tried again for a while.
	 */
	@EventListener(ApplicationReadyEvent.class)
	void warmUp() {
		if (!embedder.enabled()) {
			return;
		}
		Thread.ofVirtual().name("passage-vectors").start(() -> {
			for (int attempt = 0; attempt < WARM_UP_ATTEMPTS; attempt++) {
				List.of("en", "hi").forEach(l -> retrieve("pension", l, null, null));
				if (Instant.now().isAfter(embedderBackAt)) {
					return;
				}
				try {
					Thread.sleep(Duration.between(Instant.now(), embedderBackAt));
				} catch (InterruptedException e) {
					return;
				}
			}
		});
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

	/** What the embedder found; {@code ran} is false when it is off or failed. */
	private record Semantic(boolean ran, List<Candidate> close) {

		static final Semantic NOT_RUN = new Semantic(false, List.of());
	}

	/** Removes personal numbers before the question is used for anything. */
	public static String redact(String question) {
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
		List<Candidate> scored = new ArrayList<>();
		if (!words.isEmpty() && (double) known / words.size() >= MIN_COVERAGE) {
			long bestPassage = 0;
			for (int i = 0; i < candidates.size(); i++) {
				Set<String> hay = texts.get(i);
				long matched = words.stream().filter(w -> TextMatch.matches(w, hay)).count();
				bestPassage = Math.max(bestPassage, matched);
				double score = words.stream().filter(w -> TextMatch.matches(w, hay)).mapToDouble(idf::get).sum();
				if (score > 0) {
					scored.add(new Candidate(candidates.get(i).passage(), score));
				}
			}
			if ((double) bestPassage / words.size() < MIN_PASSAGE_COVERAGE) {
				scored.clear();
			}
			scored.sort(Comparator.comparingDouble(Candidate::score).reversed());
		}
		Semantic semantic = semantic(redact(question), candidates);
		// With a working embedder, shared words are not enough: something must be close in meaning.
		scored = semantic.ran() && semantic.close().isEmpty() ? List.of() : fuse(scored, semantic.close());

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

	/** Passages close in meaning to the question, best first. */
	private Semantic semantic(String question, List<Candidate> candidates) {
		if (!embedder.enabled() || question.isBlank() || Instant.now().isBefore(embedderBackAt)) {
			return Semantic.NOT_RUN;
		}
		try {
			List<String> missing = candidates.stream().map(c -> embedded(c.passage())).distinct()
					.filter(t -> !passageVectors.containsKey(vectorKey(t))).toList();
			if (!missing.isEmpty()) {
				List<float[]> vectors = embedder.embed(missing, Embedder.Kind.PASSAGE);
				for (int i = 0; i < missing.size(); i++) {
					passageVectors.put(vectorKey(missing.get(i)), vectors.get(i));
				}
			}
			float[] q = embedder.embed(List.of(question), Embedder.Kind.QUERY).getFirst();
			List<Candidate> out = new ArrayList<>();
			for (Candidate c : candidates) {
				double similarity = cosine(q, passageVectors.get(vectorKey(embedded(c.passage()))));
				if (similarity >= minSimilarity) {
					out.add(new Candidate(c.passage(), similarity));
				}
			}
			out.sort(Comparator.comparingDouble(Candidate::score).reversed());
			return new Semantic(true, out);
		} catch (Embedder.EmbedException e) {
			embedderBackAt = Instant.now().plus(EMBED_RETRY);
			log.warn("Embedding failed, using keywords only: {} ({})", e.getMessage(), e.getCause() == null ? "no cause" : e.getCause().toString());
			return Semantic.NOT_RUN;
		}
	}

	/**
	 * What is embedded for a passage. A rule alone often lacks its subject ("Age 18 to 40"); with
	 * the scheme's name the dev split's hit rate went from 83% to 98% in English, 60% to 98% in Hindi.
	 */
	private static String embedded(Passage p) {
		return p.schemeName().text() + ": " + p.text().text();
	}

	private String vectorKey(String text) {
		return embedder.modelId() + "\n" + text;
	}

	static double cosine(float[] a, float[] b) {
		double dot = 0;
		double na = 0;
		double nb = 0;
		for (int i = 0; i < a.length && i < b.length; i++) {
			dot += a[i] * b[i];
			na += a[i] * a[i];
			nb += b[i] * b[i];
		}
		return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
	}

	/** Reciprocal rank fusion: a passage ranked well by either list rises. Keywords alone pass through. */
	private static List<Candidate> fuse(List<Candidate> keyword, List<Candidate> semantic) {
		if (semantic.isEmpty()) {
			return keyword;
		}
		Map<Passage, Double> scores = new java.util.LinkedHashMap<>();
		for (List<Candidate> list : List.of(keyword, semantic)) {
			for (int i = 0; i < list.size(); i++) {
				scores.merge(list.get(i).passage(), 1.0 / (RRF_K + i + 1), Double::sum);
			}
		}
		return scores.entrySet().stream().map(e -> new Candidate(e.getKey(), e.getValue()))
				.sorted(Comparator.comparingDouble(Candidate::score).reversed()).toList();
	}

	/** The scheme's name in every language it has, as a {@code {en, hi, …}} object. */
	private static JsonNode namesOf(JsonNode scheme) {
		ObjectNode names = JsonNodeFactory.instance.objectNode();
		scheme.path("content").properties().forEach(e -> names.put(e.getKey(), e.getValue().path("name").asString("")));
		return names;
	}

	/** Same staleness test as the rules engine (§9.5), for one source. */
	private static boolean isStale(JsonNode scheme, JsonNode source) {
		if (!"published".equals(scheme.path("status").asString("")) || org.jansathi.rules.RulesEngine.hasChangedSource(scheme)) {
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
