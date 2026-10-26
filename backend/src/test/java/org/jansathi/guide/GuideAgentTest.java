package org.jansathi.guide;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.jansathi.provider.llm.LlmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The guide's tool loop with a scripted model: tools run for real, replies are checked. */
@SpringBootTest
@AutoConfigureMockMvc
class GuideAgentTest {

	private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	/** Plays back a script of turns; records what the model was shown. */
	static class ScriptedModel implements LlmProvider {

		final Deque<Turn> script = new ArrayDeque<>();
		final Deque<List<LlmProvider.Message>> seen = new ArrayDeque<>();

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
			assertThat(system).contains("Saathi").doesNotContain("{lang}");
			seen.add(List.copyOf(messages));
			return script.pop();
		}
	}

	@TestConfiguration
	static class Config {

		@Bean
		@Primary
		ScriptedModel scriptedModel() {
			return new ScriptedModel();
		}
	}

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> "jdbc:h2:mem:guide;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	ScriptedModel model;

	@BeforeEach
	void reset() {
		model.script.clear();
		model.seen.clear();
	}

	private String signIn(String phone) throws Exception {
		JsonNode sent = MAPPER.readTree(mvc.perform(post("/api/v1/auth/code").contentType(JSON).content("{\"phone\": \"" + phone + "\"}"))
				.andReturn().getResponse().getContentAsString());
		JsonNode in = MAPPER.readTree(mvc.perform(post("/api/v1/auth/verify").contentType(JSON)
				.content("{\"phone\": \"" + phone + "\", \"code\": \"" + sent.path("dev_code").asString() + "\"}")).andReturn().getResponse().getContentAsString());
		return "Bearer " + in.path("token").asString();
	}

	private static LlmProvider.ToolCall call(String id, String name, String args) {
		return new LlmProvider.ToolCall(id, name, MAPPER.readTree(args));
	}

	private JsonNode say(String auth, String text) throws Exception {
		return MAPPER.readTree(mvc.perform(post("/api/v1/guide/messages").header("Authorization", auth).contentType(JSON)
				.content("{\"text\": " + MAPPER.writeValueAsString(text) + ", \"lang\": \"en\"}")).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString());
	}

	@Test
	void guideAsksRecordsAndExplainsWithTools() throws Exception {
		String me = signIn("9876511001");
		model.script.add(new LlmProvider.Turn(null, List.of(call("c1", "record_answer", "{\"field\": \"age\", \"value\": \"60_69\"}")), "m"));
		model.script.add(new LlmProvider.Turn(null, List.of(call("c2", "next_question", "{}")), "m"));
		model.script.add(new LlmProvider.Turn("Thank you. Are you a woman, a man or transgender?", List.of(), "m"));

		JsonNode r = say(me, "I am 65 years old");

		assertThat(r.path("reply").asString()).startsWith("Thank you");
		assertThat(r.path("recorded").get(0).path("field").asString()).isEqualTo("age");
		assertThat(r.path("question").path("field").asString()).isEqualTo("gender");
		assertThat(r.path("household").path("members").get(0).path("profile").path("age").path("max").asInt()).isEqualTo(69);
		// The model saw the tool results it asked for.
		List<LlmProvider.Message> last = model.seen.getLast();
		assertThat(last).anySatisfy(m -> assertThat(m.role()).isEqualTo("tool"));
		assertThat(last.stream().filter(m -> "tool".equals(m.role())).map(LlmProvider.Message::content).toList())
				.anySatisfy(c -> assertThat(c).contains("\"field\":\"gender\""));
	}

	@Test
	void invalidAnswersAreRejectedByTheToolNotSaved() throws Exception {
		String me = signIn("9876511002");
		model.script.add(new LlmProvider.Turn(null, List.of(call("c1", "record_answer", "{\"field\": \"age\", \"value\": \"sixty five\"}")), "m"));
		model.script.add(new LlmProvider.Turn("Which of these is your age group?", List.of(), "m"));

		JsonNode r = say(me, "sixty five");

		assertThat(r.path("recorded")).isEmpty();
		assertThat(model.seen.getLast().getLast().content()).contains("not an option");
	}

	@Test
	void promisesAreNeverShown() throws Exception {
		String me = signIn("9876511003");
		model.script.add(new LlmProvider.Turn("Good news, you are eligible for the old age pension!", List.of(), "m"));
		model.script.add(new LlmProvider.Turn("You will get the pension.", List.of(), "m"));

		JsonNode r = say(me, "Can I get a pension?");

		assertThat(r.path("reply").isNull()).isTrue();
		assertThat(r.path("fallback").asString()).isEqualTo("unsafe");
	}

	@Test
	void askingForAHelperCreatesARequest() throws Exception {
		String me = signIn("9876511004");
		model.script.add(new LlmProvider.Turn(null, List.of(call("c1", "ask_human_helper", "{\"include_conversation\": false}")), "m"));
		model.script.add(new LlmProvider.Turn("I have asked a helper to look at your answers.", List.of(), "m"));

		JsonNode r = say(me, "Yes please, I want a person to help me");

		assertThat(r.path("help_requested").asBoolean()).isTrue();
	}
}
