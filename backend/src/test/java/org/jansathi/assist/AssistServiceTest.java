package org.jansathi.assist;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.jansathi.JansathiProperties;
import org.jansathi.content.BundleRepository;
import org.jansathi.provider.llm.LlmConfig;
import org.jansathi.provider.llm.LlmProvider;
import org.jansathi.rules.Confidence;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** The assistant pipeline with a scripted model: validation, retry, and every fallback path. */
class AssistServiceTest {

	private static final ObjectMapper JSON = JsonMapper.builder().build();
	private final BundleRepository bundles =
			new BundleRepository(new JansathiProperties(null, true, List.of(), null, null, null, null), JSON);
	private final Retriever retriever = new Retriever(bundles);
	private final AnswerValidator validator = new AnswerValidator(JSON, new CopyRules(JSON));

	/** Returns the scripted replies in order; an exception entry is thrown instead. */
	static class ScriptedLlm implements LlmProvider {

		final Deque<Object> replies = new ArrayDeque<>();
		int calls;

		ScriptedLlm(Object... replies) {
			this.replies.addAll(List.of(replies));
		}

		@Override
		public boolean enabled() {
			return true;
		}

		@Override
		public String modelId() {
			return "scripted";
		}

		@Override
		public Turn chat(String system, List<Message> messages, List<Tool> tools, CompletionOptions options) {
			calls++;
			assertThat(system).doesNotContain("{lang}");
			Object next = replies.pop();
			if (next instanceof RuntimeException e) {
				throw e;
			}
			return new Turn((String) next, List.of(), "scripted");
		}
	}

	private AssistService service(LlmProvider llm) {
		return new AssistService(bundles, retriever, llm, validator, JSON);
	}

	@Test
	void withoutModelReturnsCitedPassagesOnly() {
		var r = service(LlmConfig.DISABLED).ask("widow pension age", "en", null, null);

		assertThat(r.status()).isEqualTo(AssistService.Status.SOURCES_ONLY);
		assertThat(r.answer()).isNull();
		assertThat(r.passages()).isNotEmpty().allSatisfy(p -> assertThat(p.source().path("url").asString()).startsWith("https://"));
		assertThat(r.citations()).hasSize(r.passages().size());
	}

	@Test
	void validAnswerIsReturnedWithItsCitations() {
		var llm = new ScriptedLlm("{\"status\":\"answered\",\"answer\":\"The age rule is in this passage [p1].\",\"citations\":[\"p1\"],\"follow_up\":\"How old are you?\"}");

		var r = service(llm).ask("widow pension age", "en", null, null);

		assertThat(r.status()).isEqualTo(AssistService.Status.ANSWERED);
		assertThat(r.answer()).contains("[p1]");
		assertThat(r.citations()).containsExactly("p1");
		assertThat(r.promptVersion()).isEqualTo("assistant.v1");
		// Drafts are never presented as verified.
		assertThat(r.label()).isEqualTo(Confidence.UNCERTAIN);
	}

	@Test
	void invalidReplyIsRetriedOnce() {
		var llm = new ScriptedLlm("not json", "{\"status\":\"answered\",\"answer\":\"The age rule is in this passage [p1].\",\"citations\":[\"p1\"]}");

		var r = service(llm).ask("widow pension age", "en", null, null);

		assertThat(llm.calls).isEqualTo(2);
		assertThat(r.status()).isEqualTo(AssistService.Status.ANSWERED);
	}

	@Test
	void twoBadRepliesMeanNoOfficialSource() {
		String promise = "{\"status\":\"answered\",\"answer\":\"You are eligible for this pension [p1].\",\"citations\":[\"p1\"]}";
		var llm = new ScriptedLlm(promise, promise);

		var r = service(llm).ask("widow pension age", "en", null, null);

		assertThat(r.status()).isEqualTo(AssistService.Status.NOT_FOUND);
		assertThat(r.passages()).isEmpty();
	}

	@Test
	void failingModelDegradesToPassages() {
		var llm = new ScriptedLlm(new LlmProvider.LlmException("timeout", null));

		var r = service(llm).ask("widow pension age", "en", null, null);

		assertThat(r.status()).isEqualTo(AssistService.Status.SOURCES_ONLY);
		assertThat(r.passages()).isNotEmpty();
	}

	@Test
	void noPassageMeansNoModelCall() {
		var llm = new ScriptedLlm();

		var r = service(llm).ask("cricket score today", "en", null, null);

		assertThat(r.status()).isEqualTo(AssistService.Status.NOT_FOUND);
		assertThat(llm.calls).isZero();
	}

	@Test
	void personalNumbersNeverReachTheModel() {
		var llm = new ScriptedLlm("{\"status\":\"not_found\"}") {
			@Override
			public Completion complete(String system, List<Message> messages, CompletionOptions options) {
				assertThat(messages.getFirst().content()).doesNotContain("1234");
				return super.complete(system, messages, options);
			}
		};

		service(llm).ask("widow pension age, my aadhaar is 1234 5678 9012", "en", null, null);

		assertThat(llm.calls).isEqualTo(1);
	}
}
