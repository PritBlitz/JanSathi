package org.jansathi.assist;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.function.Predicate;
import org.jansathi.JansathiProperties;
import org.jansathi.content.BundleRepository;
import org.jansathi.provider.embed.Embedder;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Keyword and vector results fused, with a two-dimensional stand-in for a real embedding model. */
class FusedRetrievalTest {

	private static final BundleRepository BUNDLES =
			new BundleRepository(new JansathiProperties(null, true, true, List.of(), null, null, null, null, null, null), JsonMapper.builder().build());

	/** Puts anything the predicate accepts on one axis and everything else on the other. */
	private static Embedder axis(Predicate<String> about) {
		return new Embedder() {
			@Override
			public boolean enabled() {
				return true;
			}

			@Override
			public String modelId() {
				return "test";
			}

			@Override
			public List<float[]> embed(List<String> texts, Kind kind) {
				return texts.stream().map(t -> about.test(t) ? new float[] {1, 0} : new float[] {0, 1}).toList();
			}
		};
	}

	private static final Predicate<String> WIDOW = t -> t.contains("Widow") || t.contains("husband");

	@Test
	void aQuestionWithNoKeywordInCommonIsFoundByMeaning() {
		String question = "my husband passed away";
		assertThat(new Retriever(BUNDLES).retrieve(question, "en", null, null)).isEmpty();

		var passages = new Retriever(BUNDLES, axis(WIDOW)).retrieve(question, "en", null, null);

		assertThat(passages).isNotEmpty();
		assertThat(passages.getFirst().scheme()).isEqualTo("ignwps");
		assertThat(passages.getFirst().id()).isEqualTo("p1");
	}

	@Test
	void aQuestionFarFromEveryPassageStillGetsNoSource() {
		Embedder nothingClose = axis(t -> t.contains("recipe"));
		assertThat(new Retriever(BUNDLES, nothingClose).retrieve("recipe xyzzy", "en", null, null)).isEmpty();
	}

	@Test
	void whenTheEmbedderFailsKeywordResultsStillComeBack() {
		Embedder broken = new Embedder() {
			@Override
			public boolean enabled() {
				return true;
			}

			@Override
			public String modelId() {
				return "broken";
			}

			@Override
			public List<float[]> embed(List<String> texts, Kind kind) {
				throw new EmbedException("down", null);
			}
		};

		var withBroken = new Retriever(BUNDLES, broken).retrieve("widow pension age", "en", null, null);
		var keywordOnly = new Retriever(BUNDLES).retrieve("widow pension age", "en", null, null);

		assertThat(withBroken).isEqualTo(keywordOnly);
	}
}
