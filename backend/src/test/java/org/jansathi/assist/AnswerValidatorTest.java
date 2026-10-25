package org.jansathi.assist;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** docs/RAG_ASSISTANT.md §5, check by check. */
class AnswerValidatorTest {

	private static final ObjectMapper JSON = JsonMapper.builder().build();
	private final AnswerValidator validator = new AnswerValidator(JSON, new CopyRules(JSON));
	private static final Set<String> RETRIEVED = Set.of("p1", "p2");

	private AnswerValidator.Verdict check(String answer, String citations) {
		return validator.check("{\"status\":\"answered\",\"answer\":" + JSON.writeValueAsString(answer) + ",\"citations\":" + citations + "}", RETRIEVED);
	}

	@Test
	void acceptsACitedAnswer() {
		var v = check("Age 60 or more [p1]. Ask at the block office [p2].", "[\"p1\",\"p2\"]");

		assertThat(v.ok()).isPresent();
		assertThat(v.ok().get().citations()).containsExactly("p1", "p2");
	}

	@Test
	void acceptsJsonInACodeFence() {
		var v = validator.check("```json\n{\"status\":\"answered\",\"answer\":\"Age 60 [p1].\",\"citations\":[\"p1\"]}\n```", RETRIEVED);

		assertThat(v.ok()).isPresent();
	}

	@Test
	void notFoundIsAValidReply() {
		var v = validator.check("{\"status\":\"not_found\"}", RETRIEVED);

		assertThat(v.ok()).hasValueSatisfying(c -> assertThat(c.notFound()).isTrue());
	}

	@Test
	void rejectsNonJson() {
		assertThat(validator.check("Sure! Age 60.", RETRIEVED).problem()).isEqualTo("reply is not JSON");
	}

	@Test
	void rejectsACitationThatWasNotRetrieved() {
		assertThat(check("Age 60 [p9].", "[\"p9\"]").problem()).isEqualTo("cites a passage that was not retrieved");
	}

	@Test
	void rejectsAnInlineCitationMissingFromTheList() {
		assertThat(check("Age 60 [p1]. Office [p2].", "[\"p1\"]").problem()).contains("p2");
	}

	@Test
	void rejectsAnAnswerWithoutInlineCitations() {
		assertThat(check("Age 60 or more.", "[\"p1\"]").problem()).isEqualTo("no inline [pN] citation in the answer");
	}

	@Test
	void rejectsPromisesInEnglishAndHindi() {
		assertThat(check("You are eligible [p1].", "[\"p1\"]").problem()).startsWith("banned phrase");
		assertThat(check("आप पात्र हैं [p1]।", "[\"p1\"]").problem()).startsWith("banned phrase");
		assertThat(check("This is guaranteed [p1].", "[\"p1\"]").problem()).startsWith("banned phrase");
	}

	@Test
	void allowsOfficialNamesThatContainGuarantee() {
		assertThat(check("Show your Rozgar Guarantee Card [p1].", "[\"p1\"]").ok()).isPresent();
	}

	@Test
	void cutsOverlongAnswersAtASentence() {
		String sentence = "This sentence has exactly ten words in it right here [p1]. ";
		String answer = sentence.repeat(25);

		String out = check(answer, "[\"p1\"]").ok().orElseThrow().answer();

		assertThat(AnswerValidator.words(out)).isLessThanOrEqualTo(AnswerValidator.HARD_WORDS);
		assertThat(out).endsWith("[p1].");
	}
}
