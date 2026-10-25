package org.jansathi.assist;

import com.fasterxml.jackson.annotation.JsonValue;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jansathi.assist.Retriever.Passage;
import org.jansathi.content.BundleRepository;
import org.jansathi.content.ContentBundle;
import org.jansathi.content.Languages;
import org.jansathi.provider.llm.LlmProvider;
import org.jansathi.provider.llm.LlmProvider.CompletionOptions;
import org.jansathi.provider.llm.LlmProvider.LlmException;
import org.jansathi.provider.llm.LlmProvider.Message;
import org.jansathi.rules.Confidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * The assistant pipeline (docs/RAG_ASSISTANT.md §1): redact → retrieve → gate → explain → validate
 * → respond. No passage means "no official source", without calling the model. The model only
 * explains the passages; when it is off, slow or fails, the answer is the cited passages alone.
 * Questions are never logged.
 */
@Service
public class AssistService {

	public static final String PROMPT_VERSION = "assistant.v1";
	private static final Logger log = LoggerFactory.getLogger(AssistService.class);
	private static final CompletionOptions OPTIONS = new CompletionOptions(600, 0.1, true);

	private final BundleRepository bundles;
	private final Retriever retriever;
	private final LlmProvider llm;
	private final AnswerValidator validator;
	private final ObjectMapper json;
	private final String systemPrompt;

	public AssistService(BundleRepository bundles, Retriever retriever, LlmProvider llm, AnswerValidator validator, ObjectMapper json) {
		this.bundles = bundles;
		this.retriever = retriever;
		this.llm = llm;
		this.validator = validator;
		this.json = json;
		try {
			this.systemPrompt = new ClassPathResource("prompts/" + PROMPT_VERSION + ".txt").getContentAsString(StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("Prompt " + PROMPT_VERSION + " is missing", e);
		}
	}

	public enum Status {
		/** A validated explanation of the cited passages. */
		ANSWERED,
		/** Cited passages without an explanation (no model configured, or it failed). */
		SOURCES_ONLY,
		/** Nothing official matched: the client shows "I couldn't find an official source for this". */
		NOT_FOUND;

		@JsonValue
		public String json() {
			return name().toLowerCase();
		}
	}

	/**
	 * @param answer the model's explanation with [pN] citations; null unless status is answered
	 * @param citations passage ids the answer cites (all of them for sources_only)
	 * @param label weakest confidence among the cited passages; uncertain when any is stale
	 * @param passages every retrieved passage, each with its source and clause
	 * @param model and promptVersion are recorded so a bad answer can be traced
	 */
	public record AskResponse(
			String version,
			boolean preview,
			String lang,
			Status status,
			String answer,
			String followUp,
			List<String> citations,
			Confidence label,
			List<Passage> passages,
			String model,
			String promptVersion) {}

	public AskResponse ask(String question, String lang, String schemeSlug, String state) {
		ContentBundle b = bundles.get();
		String l = Languages.orEnglish(lang);
		String clean = Retriever.redact(question);
		List<Passage> passages = retriever.retrieve(clean, l, schemeSlug, state);
		if (passages.isEmpty()) {
			return notFound(b, l);
		}
		List<String> allIds = passages.stream().map(Passage::id).toList();
		if (!llm.enabled()) {
			return new AskResponse(b.version(), b.preview(), l, Status.SOURCES_ONLY, null, null, allIds, label(passages, allIds), passages, null, null);
		}

		String user = userMessage(clean, l, passages);
		Set<String> ids = Set.copyOf(allIds);
		String system = systemPrompt.replace("{lang}", l);
		for (int attempt = 1; attempt <= 2; attempt++) {
			try {
				var completion = llm.complete(system, List.of(Message.user(user)), OPTIONS);
				var verdict = validator.check(completion.text(), ids);
				if (verdict.ok().isPresent()) {
					var ok = verdict.ok().get();
					if (ok.notFound()) {
						return notFound(b, l);
					}
					return new AskResponse(b.version(), b.preview(), l, Status.ANSWERED, ok.answer(), ok.followUp(), ok.citations(),
							label(passages, ok.citations()), passages, completion.model(), PROMPT_VERSION);
				}
				log.warn("Assistant reply rejected (attempt {}): {}", attempt, verdict.problem());
			} catch (LlmException e) {
				// A slow or failing model degrades the answer to passages; it never fails the request.
				log.warn("LLM unavailable: {}", e.getMessage());
				return new AskResponse(b.version(), b.preview(), l, Status.SOURCES_ONLY, null, null, allIds, label(passages, allIds), passages, null, null);
			}
		}
		// Two invalid replies: say no official source was found rather than show a bad answer (§4).
		return notFound(b, l);
	}

	private static AskResponse notFound(ContentBundle b, String lang) {
		return new AskResponse(b.version(), b.preview(), lang, Status.NOT_FOUND, null, null, List.of(), Confidence.UNCERTAIN, List.of(), null, null);
	}

	/** Weakest confidence among the cited passages; any stale cited passage makes it uncertain. */
	static Confidence label(List<Passage> passages, List<String> cited) {
		Confidence label = Confidence.CONFIRMED;
		for (Passage p : passages) {
			if (!cited.contains(p.id())) {
				continue;
			}
			if (p.stale()) {
				return Confidence.UNCERTAIN;
			}
			if (p.confidence().ordinal() < label.ordinal()) {
				label = p.confidence();
			}
		}
		return label;
	}

	/** The question and passages as JSON (§3). No profile fields are sent to the model. */
	private String userMessage(String question, String lang, List<Passage> passages) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("question", question);
		body.put("lang", lang);
		List<Map<String, Object>> list = new ArrayList<>();
		for (Passage p : passages) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", p.id());
			m.put("scheme", p.schemeName().text());
			m.put("text", p.text().text());
			m.put("source", p.source().path("title").asString(""));
			m.put("locator", p.locator());
			list.add(m);
		}
		body.put("passages", list);
		return json.writeValueAsString(body);
	}

}
