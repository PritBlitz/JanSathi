package org.jansathi.assist;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jansathi.JansathiProperties;
import org.jansathi.content.BundleRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Retrieval evaluation on {@code eval/rag/*.jsonl} (docs/RAG_ASSISTANT.md §7). Measures the part
 * that exists today, retrieval and the "no official source" gate:
 *
 * <ul>
 *   <li>hit: answerable questions whose passages (the ones the user sees, at most 6) include an expected scheme;
 *   <li>correct refusal: unanswerable questions that retrieve nothing (so the answer is
 *       "I couldn't find an official source").
 * </ul>
 *
 * The LLM metrics in §7 (hallucination, readability) need a configured model and are not run here.
 */
class RetrievalEvalTest {

	/** Thresholds for keyword retrieval; raise them when embeddings replace it. */
	static final double MIN_HIT = 0.85;
	static final double MIN_CORRECT_REFUSAL = 0.9;

	private static final ObjectMapper JSON = JsonMapper.builder().build();
	private static final Path EVAL = Path.of(System.getProperty("jansathi.repoRoot", "..")).resolve("eval/rag");

	private final Retriever retriever =
			new Retriever(new BundleRepository(new JansathiProperties(null, true, List.of(), null, null), JSON));

	private record Score(String lang, int answerable, int hits, int unanswerable, int refusals, List<String> misses) {}

	private Score score(String lang) throws IOException {
		int answerable = 0;
		int hits = 0;
		int unanswerable = 0;
		int refusals = 0;
		List<String> misses = new ArrayList<>();
		for (String line : Files.readAllLines(EVAL.resolve(lang + ".jsonl"))) {
			if (line.isBlank()) {
				continue;
			}
			JsonNode item = JSON.readTree(line);
			Set<String> expected = new java.util.HashSet<>();
			item.path("expected_schemes").forEach(s -> expected.add(s.asString()));
			var passages = retriever.retrieve(item.path("question").asString(), lang, null, null);
			if (expected.isEmpty()) {
				unanswerable++;
				if (passages.isEmpty()) {
					refusals++;
				} else {
					misses.add(item.path("id").asString() + " should find nothing, got " + passages.getFirst().scheme());
				}
			} else {
				answerable++;
				List<String> top = passages.stream().map(Retriever.Passage::scheme).toList();
				if (top.stream().anyMatch(expected::contains)) {
					hits++;
				} else {
					misses.add(item.path("id").asString() + " expected " + expected + ", got " + top);
				}
			}
		}
		return new Score(lang, answerable, hits, unanswerable, refusals, misses);
	}

	@Test
	void retrievalMeetsTheMvpThresholds() throws IOException {
		List<Score> scores = new ArrayList<>();
		for (String lang : List.of("en", "hi")) {
			scores.add(score(lang));
		}
		for (Score s : scores) {
			System.out.printf("eval %s: hit %d/%d, correct refusal %d/%d%n", s.lang(), s.hits(), s.answerable(), s.refusals(), s.unanswerable());
			s.misses().forEach(m -> System.out.println("  miss " + m));
		}
		for (Score s : scores) {
			String misses = s.misses().stream().collect(Collectors.joining("\n"));
			assertThat((double) s.hits() / s.answerable()).as("hit in %s\n%s", s.lang(), misses).isGreaterThanOrEqualTo(MIN_HIT);
			assertThat((double) s.refusals() / s.unanswerable()).as("correct refusal in %s\n%s", s.lang(), misses).isGreaterThanOrEqualTo(MIN_CORRECT_REFUSAL);
		}
	}
}
