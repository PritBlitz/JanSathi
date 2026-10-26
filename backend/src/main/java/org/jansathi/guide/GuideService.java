package org.jansathi.guide;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.jansathi.account.Accounts.User;
import org.jansathi.account.Crypto;
import org.jansathi.account.Households;
import org.jansathi.assist.CopyRules;
import org.jansathi.assist.Retriever.Passage;
import org.jansathi.content.Languages;
import org.jansathi.provider.llm.LlmProvider;
import org.jansathi.provider.llm.LlmProvider.CompletionOptions;
import org.jansathi.provider.llm.LlmProvider.LlmException;
import org.jansathi.provider.llm.LlmProvider.Message;
import org.jansathi.provider.llm.LlmProvider.ToolCall;
import org.jansathi.web.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Saathi, the AI guide each signed-in person gets (and the assistant helpers get). It talks in the
 * person's language, asks the questionnaire's questions one at a time, records answers, and
 * explains the rules engine's results with their sources, using {@link GuideTools} only. Every reply
 * is checked against the same copy rules as the app before anyone sees it.
 *
 * <p>Without a configured model the guide still works: it asks the next question with tap-to-answer
 * options, and the app shows its own localised text.
 */
@Service
public class GuideService {

	public static final String PROMPT = "guide.v1";
	public static final String HELPER_PROMPT = "guide-helper.v1";
	private static final Logger log = LoggerFactory.getLogger(GuideService.class);
	private static final CompletionOptions OPTIONS = new CompletionOptions(700, 0.2, false);
	private static final int MAX_TOOL_ROUNDS = 6;
	private static final int HISTORY = 20;
	private static final int MAX_MESSAGE = 600;
	private static final int MESSAGES_PER_10_MIN = 30;
	private static final Pattern PERSONAL_NUMBER = Pattern.compile("\\d(?:[\\s-]?\\d){8,}");

	private final LlmProvider llm;
	private final GuideTools tools;
	private final Households households;
	private final CopyRules copy;
	private final Crypto crypto;
	private final JdbcClient jdbc;
	private final ObjectMapper json;
	private final Map<String, String> prompts = new ConcurrentHashMap<>();
	private final Map<String, Deque<Instant>> recent = new ConcurrentHashMap<>();

	public GuideService(LlmProvider llm, GuideTools tools, Households households, CopyRules copy, Crypto crypto, JdbcClient jdbc, ObjectMapper json) {
		this.llm = llm;
		this.tools = tools;
		this.households = households;
		this.copy = copy;
		this.crypto = crypto;
		this.jdbc = jdbc;
		this.json = json;
	}

	private String prompt(String name, String lang) {
		String text = prompts.computeIfAbsent(name, n -> {
			try {
				return new ClassPathResource("prompts/" + n + ".txt").getContentAsString(StandardCharsets.UTF_8);
			} catch (IOException e) {
				throw new UncheckedIOException("Prompt " + n + " is missing", e);
			}
		});
		return text.replace("{lang}", lang);
	}

	/**
	 * One turn's result.
	 *
	 * @param reply the guide's words, or null when the app should show its own text ({@code fallback})
	 * @param fallback why there is no reply: {@code no_model}, {@code unavailable} or {@code unsafe}
	 * @param question the next question with its options, for tap-to-answer
	 * @param recorded answers saved this turn (the app shows them with an undo)
	 * @param schemes schemes mentioned, for cards with their sources
	 * @param citations official passages the guide searched
	 * @param household the saved household after this turn
	 */
	public record Reply(
			String reply,
			String fallback,
			ObjectNode question,
			List<Map<String, Object>> recorded,
			List<String> schemes,
			List<Passage> citations,
			boolean helpRequested,
			ObjectNode household,
			String model) {}

	public record Line(String role, String text, Instant at) {}

	/** The person's saved conversation, oldest first. */
	public List<Line> history(String userId, int limit) {
		List<Line> lines = new ArrayList<>(jdbc.sql("SELECT role, content_enc, created_at FROM guide_message WHERE user_id = :u ORDER BY created_at DESC LIMIT :n")
				.param("u", userId).param("n", limit)
				.query((rs, i) -> new Line(rs.getString("role"), crypto.decrypt(rs.getString("content_enc")), rs.getObject("created_at", OffsetDateTime.class).toInstant()))
				.list());
		java.util.Collections.reverse(lines);
		return lines;
	}

	public void forget(String userId) {
		jdbc.sql("DELETE FROM guide_message WHERE user_id = :u").param("u", userId).update();
	}

	private void save(String userId, String role, String text) {
		jdbc.sql("INSERT INTO guide_message (id, user_id, role, content_enc, created_at) VALUES (:id, :u, :r, :c, :now)")
				.param("id", UUID.randomUUID().toString()).param("u", userId).param("r", role).param("c", crypto.encrypt(text))
				.param("now", OffsetDateTime.now(ZoneOffset.UTC)).update();
	}

	private void rateLimit(String userId) {
		Deque<Instant> times = recent.computeIfAbsent(userId, u -> new ArrayDeque<>());
		synchronized (times) {
			Instant cutoff = Instant.now().minusSeconds(600);
			while (!times.isEmpty() && times.peekFirst().isBefore(cutoff)) {
				times.pollFirst();
			}
			if (times.size() >= MESSAGES_PER_10_MIN) {
				throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many messages. Wait a few minutes.");
			}
			times.addLast(Instant.now());
		}
	}

	/**
	 * A turn for the person themselves.
	 *
	 * @param text what they typed or said (may be empty when they tapped an answer)
	 * @param tapped an answer tapped on a question card: {@code {field, value}}; saved without the model
	 */
	public Reply turn(User user, String text, JsonNode tapped, String lang) {
		String l = Languages.orEnglish(lang);
		String message = text == null ? "" : PERSONAL_NUMBER.matcher(text.strip()).replaceAll("[number removed]");
		if (message.length() > MAX_MESSAGE) {
			throw new BadRequestException("A message can have at most " + MAX_MESSAGE + " characters");
		}
		rateLimit(user.id());
		ObjectNode household = households.get(user.id());
		var events = new GuideTools.Events();
		var session = new GuideTools.Session(household, l, user, false, events);

		if (tapped != null && tapped.hasNonNull("field")) {
			JsonNode r = tools.run("record_answer", tapped, session);
			if (r.has("error")) {
				throw new BadRequestException(r.get("error").asString());
			}
			if (message.isEmpty()) {
				message = r.path("answer").asString("");
			}
		}

		List<Message> conversation = new ArrayList<>();
		history(user.id(), HISTORY).forEach(line -> conversation.add("user".equals(line.role()) ? Message.user(line.text()) : Message.assistant(line.text())));
		if (!message.isEmpty()) {
			conversation.add(Message.user(message));
			save(user.id(), "user", message);
		}

		Result result = llm.enabled() && !conversation.isEmpty()
				? converse(prompt(PROMPT, l), conversation, session)
				: new Result(null, llm.enabled() ? null : "no_model", null);
		if (result.text() != null) {
			save(user.id(), "guide", result.text());
		}
		if (events.question == null) {
			events.question = tools.question(session);
		}
		if (events.changed) {
			household = households.put(user.id(), household);
		}
		return new Reply(result.text(), result.fallback(), events.question, events.recorded, List.copyOf(events.schemes), events.citations,
				events.helpRequestId != null, household, result.model());
	}

	/** A helper's question about a case they hold: read-only tools, nothing saved. */
	public Reply assistHelper(User helper, ObjectNode household, List<Message> conversation, String lang) {
		rateLimit(helper.id());
		String l = Languages.orEnglish(lang);
		var events = new GuideTools.Events();
		var session = new GuideTools.Session(household, l, helper, true, events);
		Result result = llm.enabled() ? converse(prompt(HELPER_PROMPT, l), conversation, session) : new Result(null, "no_model", null);
		return new Reply(result.text(), result.fallback(), tools.question(session), List.of(), List.copyOf(events.schemes), events.citations,
				false, null, result.model());
	}

	private record Result(String text, String fallback, String model) {}

	/** The tool loop, then the copy check with one retry. */
	private Result converse(String system, List<Message> conversation, GuideTools.Session session) {
		List<Message> messages = new ArrayList<>(conversation);
		try {
			for (int attempt = 1; attempt <= 2; attempt++) {
				LlmProvider.Turn turn = null;
				for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
					turn = llm.chat(system, messages, tools.definitions(session.readOnly()), OPTIONS);
					if (!turn.wantsTools()) {
						break;
					}
					messages.add(Message.assistantCalls(turn.toolCalls()));
					for (ToolCall call : turn.toolCalls()) {
						JsonNode out = tools.run(call.name(), call.arguments() == null ? JsonNodeFactory.instance.objectNode() : call.arguments(), session);
						messages.add(Message.toolResult(call.id(), json.writeValueAsString(out)));
					}
				}
				String text = turn == null || turn.text() == null ? "" : turn.text().strip();
				List<String> hits = copy.hits(text);
				if (!text.isEmpty() && hits.isEmpty() && !PERSONAL_NUMBER.matcher(text).find()) {
					return new Result(text, null, turn.model());
				}
				log.warn("Guide reply rejected (attempt {}): {}", attempt, text.isEmpty() ? "empty" : "banned phrase " + hits);
				messages.add(Message.user("(System check: your last reply was not shown because it "
						+ (text.isEmpty() ? "was empty" : "said something like a promise of eligibility or approval")
						+ ". Reply again: say 'may fit' or 'the rule says', never that the person qualifies or will receive something.)"));
			}
			return new Result(null, "unsafe", null);
		} catch (LlmException e) {
			log.warn("Guide model unavailable: {}", e.getMessage());
			return new Result(null, "unavailable", null);
		}
	}
}
