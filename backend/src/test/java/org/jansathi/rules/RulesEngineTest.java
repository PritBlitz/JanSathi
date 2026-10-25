package org.jansathi.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The worked examples from docs/RULES_ENGINE.md §5, plus edge cases from §8. */
class RulesEngineTest {

	private final RulesEngine engine = new RulesEngine(JsonMapper.builder().build());

	private static final Rule AGE_60 = new Rule("age_min", "{\">=\": [{\"var\": \"age\"}, 60]}", true, Confidence.CONFIRMED);
	private static final Rule BPL = new Rule("bpl", "{\"==\": [{\"var\": \"is_bpl\"}, true]}", true, Confidence.CONFIRMED);
	private static final List<Rule> OLD_AGE_PENSION = List.of(AGE_60, BPL);

	private static final Rule CATEGORY_SC = new Rule("category_sc", "{\"==\": [{\"var\": \"social_category\"}, \"sc\"]}", true, Confidence.CONFIRMED);
	private static final Rule POST_MATRIC = new Rule(
			"post_matric",
			"{\"in\": [{\"var\": \"education_level\"}, [\"class_11_12\", \"diploma\", \"ug\", \"pg\"]]}",
			true,
			Confidence.CONFIRMED);
	private static final Rule INCOME_MAX = new Rule("income_max", "{\"<=\": [{\"var\": \"annual_income\"}, 250000]}", true, Confidence.CONFIRMED);
	private static final Rule HOSTELLER = new Rule("hosteller_priority", "{\"==\": [{\"var\": \"is_hosteller\"}, true]}", false, Confidence.GUIDANCE);
	private static final List<Rule> SCHOLARSHIP = List.of(CATEGORY_SC, POST_MATRIC, INCOME_MAX, HOSTELLER);

	@Test
	void missingFieldGivesNeedMoreInfo() {
		SchemeEvaluation result = engine.evaluateScheme(OLD_AGE_PENSION, Map.of("age", new Band(60, 69)));

		assertThat(result.result()).isEqualTo(MatchResult.NEED_MORE_INFO);
		assertThat(result.missingFields()).containsExactly("is_bpl");
		assertThat(result.rules()).extracting(RuleTrace::outcome).containsExactly(RuleOutcome.PASS, RuleOutcome.UNKNOWN);
	}

	@Test
	void bandStraddlingTheLimitIsUnknown() {
		SchemeEvaluation result = engine.evaluateScheme(OLD_AGE_PENSION, Map.of("age", new Band(55, 64), "is_bpl", true));

		assertThat(result.result()).isEqualTo(MatchResult.NEED_MORE_INFO);
		assertThat(result.missingFields()).containsExactly("age");
	}

	@Test
	void bandBelowTheLimitIsNotEligible() {
		SchemeEvaluation result = engine.evaluateScheme(OLD_AGE_PENSION, Map.of("age", new Band(40, 49), "is_bpl", true));

		assertThat(result.result()).isEqualTo(MatchResult.NOT_ELIGIBLE);
		assertThat(result.missingFields()).isEmpty();
	}

	@Test
	void exactBoundaryValues() {
		assertThat(engine.evaluateRule(AGE_60, Map.of("age", 59)).outcome()).isEqualTo(RuleOutcome.FAIL);
		assertThat(engine.evaluateRule(AGE_60, Map.of("age", 60)).outcome()).isEqualTo(RuleOutcome.PASS);
		assertThat(engine.evaluateRule(AGE_60, Map.of("age", 61)).outcome()).isEqualTo(RuleOutcome.PASS);
		assertThat(engine.evaluateRule(INCOME_MAX, Map.of("annual_income", 249999)).outcome()).isEqualTo(RuleOutcome.PASS);
		assertThat(engine.evaluateRule(INCOME_MAX, Map.of("annual_income", 250000)).outcome()).isEqualTo(RuleOutcome.PASS);
		assertThat(engine.evaluateRule(INCOME_MAX, Map.of("annual_income", 250001)).outcome()).isEqualTo(RuleOutcome.FAIL);
	}

	@Test
	void bandUpperBoundEqualToLimitPasses() {
		Map<String, Object> profile = Map.of(
				"social_category", "sc", "education_level", "ug", "annual_income", new Band(100000, 250000));

		SchemeEvaluation result = engine.evaluateScheme(SCHOLARSHIP, profile);

		assertThat(result.result()).isEqualTo(MatchResult.LIKELY_ELIGIBLE);
	}

	@Test
	void softRulesNeverChangeTheResult() {
		Map<String, Object> profile = new HashMap<>(Map.of(
				"social_category", "sc", "education_level", "ug", "annual_income", new Band(100000, 250000)));

		assertThat(engine.evaluateScheme(SCHOLARSHIP, profile).result()).isEqualTo(MatchResult.LIKELY_ELIGIBLE);
		profile.put("is_hosteller", false);
		assertThat(engine.evaluateScheme(SCHOLARSHIP, profile).result()).isEqualTo(MatchResult.LIKELY_ELIGIBLE);
	}

	@Test
	void incomeBandStraddlingLimitAsksAgain() {
		Map<String, Object> profile = Map.of(
				"social_category", "sc", "education_level", "ug", "annual_income", new Band(200000, 300000));

		SchemeEvaluation result = engine.evaluateScheme(SCHOLARSHIP, profile);

		assertThat(result.result()).isEqualTo(MatchResult.NEED_MORE_INFO);
		assertThat(result.missingFields()).containsExactly("annual_income");
	}

	@Test
	void definiteFailWinsOverUnknown() {
		SchemeEvaluation result = engine.evaluateScheme(OLD_AGE_PENSION, Map.of("age", new Band(18, 24)));

		assertThat(result.result()).isEqualTo(MatchResult.NOT_ELIGIBLE);
	}

	@Test
	void failingUncertainHardRuleIsNeedMoreInfoNotRejection() {
		Rule uncertainAge = new Rule("age_min", AGE_60.jsonLogic(), true, Confidence.UNCERTAIN);

		SchemeEvaluation result = engine.evaluateScheme(List.of(uncertainAge, BPL), Map.of("age", 45, "is_bpl", true));

		assertThat(result.result()).isEqualTo(MatchResult.NEED_MORE_INFO);
	}

	@Test
	void enumMembership() {
		assertThat(engine.evaluateRule(POST_MATRIC, Map.of("education_level", "diploma")).outcome()).isEqualTo(RuleOutcome.PASS);
		assertThat(engine.evaluateRule(POST_MATRIC, Map.of("education_level", "not_studying")).outcome()).isEqualTo(RuleOutcome.FAIL);
	}

	@Test
	void collectsNestedVariables() {
		String logic = "{\"or\": [{\">=\": [{\"var\": \"age\"}, 70]}, {\"and\": [{\"var\": [\"is_bpl\", false]}, {\"!\": {\"var\": \"x\"}}]}]}";

		assertThat(engine.variablesOf(logic)).containsExactly("age", "is_bpl", "x");
	}

	@Test
	void invalidBandIsRejected() {
		assertThatThrownBy(() -> new Band(10, 5)).isInstanceOf(IllegalArgumentException.class);
	}
}
