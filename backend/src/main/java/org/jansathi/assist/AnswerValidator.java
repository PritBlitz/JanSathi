package org.jansathi.assist;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Checks a model reply against docs/RAG_ASSISTANT.md §5 before anyone sees it: valid JSON, every
 * citation was retrieved, every [pN] in the text is cited, at least one citation, no banned
 * phrase, and at most 180 words (cut at a sentence boundary).
 */
@Component
public class AnswerValidator {

	static final int SOFT_WORDS = 120;
	static final int HARD_WORDS = 180;
	private static final Pattern CITE = Pattern.compile("\\[(p\\d+)]");
	private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?।॥])\\s+");

	private final ObjectMapper json;
	private final CopyRules copy;

	public AnswerValidator(ObjectMapper json, CopyRules copy) {
		this.json = json;
		this.copy = copy;
	}

	/** A reply that passed. {@code notFound} means the model said the passages don't answer it. */
	public record Checked(boolean notFound, String answer, List<String> citations, String followUp) {}

	/** Why a reply was rejected; empty when it passed. */
	public record Verdict(Optional<Checked> ok, String problem) {

		static Verdict pass(Checked c) {
			return new Verdict(Optional.of(c), null);
		}

		static Verdict fail(String problem) {
			return new Verdict(Optional.empty(), problem);
		}
	}

	public Verdict check(String reply, Set<String> retrievedIds) {
		JsonNode node;
		try {
			node = json.readTree(stripFences(reply));
		} catch (JacksonException e) {
			return Verdict.fail("reply is not JSON");
		}
		String status = node.path("status").asString("");
		if (status.equals("not_found")) {
			return Verdict.pass(new Checked(true, null, List.of(), null));
		}
		if (!status.equals("answered")) {
			return Verdict.fail("status must be answered or not_found");
		}
		String answer = node.path("answer").asString("").strip();
		if (answer.isEmpty()) {
			return Verdict.fail("empty answer");
		}
		Set<String> citations = new LinkedHashSet<>();
		node.path("citations").forEach(c -> citations.add(c.asString("")));
		if (citations.isEmpty()) {
			return Verdict.fail("an answer needs at least one citation");
		}
		if (!retrievedIds.containsAll(citations)) {
			return Verdict.fail("cites a passage that was not retrieved");
		}
		Matcher m = CITE.matcher(answer);
		boolean anyInline = false;
		while (m.find()) {
			anyInline = true;
			if (!citations.contains(m.group(1))) {
				return Verdict.fail("inline citation " + m.group(1) + " is not in citations");
			}
		}
		if (!anyInline) {
			return Verdict.fail("no inline [pN] citation in the answer");
		}
		String followUp = node.path("follow_up").asString("").strip();
		List<String> hits = new ArrayList<>(copy.hits(answer));
		hits.addAll(copy.hits(followUp));
		if (!hits.isEmpty()) {
			return Verdict.fail("banned phrase " + hits.getFirst());
		}
		return Verdict.pass(new Checked(false, truncate(answer), List.copyOf(citations), followUp.isEmpty() ? null : followUp));
	}

	/** Models sometimes wrap JSON in a Markdown code fence. */
	private static String stripFences(String s) {
		String t = s.strip();
		if (t.startsWith("```")) {
			t = t.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "");
		}
		return t;
	}

	static int words(String s) {
		String t = s.strip();
		return t.isEmpty() ? 0 : t.split("\\s+").length;
	}

	/** Over the hard limit: keep whole sentences up to it (at least the first sentence). */
	static String truncate(String answer) {
		if (words(answer) <= HARD_WORDS) {
			return answer;
		}
		StringBuilder out = new StringBuilder();
		for (String sentence : SENTENCE_END.split(answer)) {
			if (!out.isEmpty() && words(out + " " + sentence) > HARD_WORDS) {
				break;
			}
			out.append(out.isEmpty() ? "" : " ").append(sentence);
		}
		return out.toString();
	}
}
