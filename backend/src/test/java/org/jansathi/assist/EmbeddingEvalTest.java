package org.jansathi.assist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jansathi.JansathiProperties;
import org.jansathi.content.BundleRepository;
import org.jansathi.provider.embed.Embedder;
import org.jansathi.provider.embed.HttpEmbedder;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keyword against fused retrieval on eval/rag with the real model, split into dev and held-out
 * (eval/README.md). Needs a running ML service, so it is skipped unless JANSATHI_EVAL_ML_URL is set:
 *
 * <pre>JANSATHI_EVAL_ML_URL=http://localhost:8090 node tools/gradle.mjs test --tests '*EmbeddingEvalTest'</pre>
 *
 * JANSATHI_EVAL_SWEEP=1 prints a threshold sweep on the dev split only; held-out numbers must never
 * be used to pick a threshold.
 */
class EmbeddingEvalTest {

	private static final ObjectMapper JSON = JsonMapper.builder().build();
	private static final Path EVAL = Path.of(System.getProperty("jansathi.repoRoot", "..")).resolve("eval/rag");
	private static final BundleRepository BUNDLES =
			new BundleRepository(new JansathiProperties(null, true, true, List.of(), null, null, null, null, null, null), JSON);

	/** Remembers vectors, so a sweep costs one pass over the questions. */
	private static final class Cached implements Embedder {
		private final Embedder real;
		private final Map<String, float[]> vectors = new HashMap<>();

		Cached(Embedder real) {
			this.real = real;
		}

		@Override
		public boolean enabled() {
			return true;
		}

		@Override
		public String modelId() {
			return real.modelId();
		}

		@Override
		public List<float[]> embed(List<String> texts, Kind kind) {
			List<String> missing = texts.stream().filter(t -> !vectors.containsKey(kind + t)).toList();
			if (!missing.isEmpty()) {
				List<float[]> got = real.embed(missing, kind);
				for (int i = 0; i < missing.size(); i++) {
					vectors.put(kind + missing.get(i), got.get(i));
				}
			}
			return texts.stream().map(t -> vectors.get(kind + t)).toList();
		}
	}

	private record Score(int answerable, int hits, int unanswerable, int refusals, List<String> misses) {

		String line() {
			return String.format("hit %d/%d (%.1f%%), correct refusal %d/%d (%.1f%%)", hits, answerable, 100.0 * hits / answerable,
					refusals, unanswerable, 100.0 * refusals / unanswerable);
		}
	}

	private static Score score(Retriever retriever, String lang, String split) throws IOException {
		return score(retriever, lang, split, EVAL.resolve(lang + ".jsonl"));
	}

	private static Score score(Retriever retriever, String lang, String split, Path file) throws IOException {
		int answerable = 0;
		int hits = 0;
		int unanswerable = 0;
		int refusals = 0;
		List<String> misses = new ArrayList<>();
		for (String line : Files.readAllLines(file)) {
			JsonNode item = line.isBlank() ? null : JSON.readTree(line);
			if (item == null || !split.equals(item.path("split").asString())) {
				continue;
			}
			Set<String> expected = new HashSet<>();
			item.path("expected_schemes").forEach(s -> expected.add(s.asString()));
			List<String> top = retriever.retrieve(item.path("question").asString(), lang, null, null).stream().map(Retriever.Passage::scheme).toList();
			if (expected.isEmpty()) {
				unanswerable++;
				if (top.isEmpty()) {
					refusals++;
				} else {
					misses.add(item.path("id").asString() + " should find nothing, got " + top.getFirst());
				}
			} else {
				answerable++;
				if (top.stream().anyMatch(expected::contains)) {
					hits++;
				} else {
					misses.add(item.path("id").asString() + " expected " + expected + ", got " + top);
				}
			}
		}
		return new Score(answerable, hits, unanswerable, refusals, misses);
	}

	@Test
	void fusionBeatsKeywordsWithTheRealModel() throws IOException {
		String url = System.getenv("JANSATHI_EVAL_ML_URL");
		assumeTrue(url != null && !url.isBlank(), "no ML service to evaluate against");
		Embedder model = new Cached(new HttpEmbedder(
				new JansathiProperties.Embed("ml-service", url, System.getenv("JANSATHI_EVAL_ML_TOKEN"), "intfloat/multilingual-e5-small", Duration.ofSeconds(30)),
				RestClient.builder()));
		Retriever keyword = new Retriever(BUNDLES);

		if (System.getenv("JANSATHI_EVAL_SWEEP") != null) {
			for (double t = 0.80; t < 0.8701; t += 0.005) {
				Retriever fused = new Retriever(BUNDLES, model, t);
				System.out.printf("sweep dev %.3f en: %s | hi: %s%n", t, score(fused, "en", "dev").line(), score(fused, "hi", "dev").line());
			}
			return;
		}

		Retriever fused = new Retriever(BUNDLES, model);
		int keywordRight = 0;
		int fusedRight = 0;
		for (String split : List.of("dev", "heldout")) {
			for (String lang : List.of("en", "hi")) {
				Score k = score(keyword, lang, split);
				Score f = score(fused, lang, split);
				System.out.printf("eval %s %s keyword: %s%n", split, lang, k.line());
				System.out.printf("eval %s %s fused:   %s%n", split, lang, f.line());
				f.misses().forEach(m -> System.out.println("  miss " + m));
				keywordRight += k.hits() + k.refusals();
				fusedRight += f.hits() + f.refusals();
			}
		}
		assertThat(fusedRight).isGreaterThan(keywordRight);

		// English held-out questions, machine translated (eval/translate_heldout.py). Passages stay in
		// English for these languages, so keywords have nothing to match and only vectors can help.
		Path translated = EVAL.resolveSibling("rag-translated");
		for (String lang : List.of("ta", "bn")) {
			Path file = translated.resolve(lang + ".jsonl");
			if (Files.exists(file)) {
				System.out.printf("eval translated %s keyword: %s%n", lang, score(keyword, lang, "heldout", file).line());
				Score f = score(fused, lang, "heldout", file);
				System.out.printf("eval translated %s fused:   %s%n", lang, f.line());
				f.misses().forEach(m -> System.out.println("  miss " + m));
			}
		}
	}
}
