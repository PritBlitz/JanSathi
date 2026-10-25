package org.jansathi.rules;

import io.github.jamsesso.jsonlogic.JsonLogic;
import io.github.jamsesso.jsonlogic.JsonLogicException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Deterministic three-state eligibility evaluation. This is the only component that decides match
 * results; the assistant only explains them. Semantics are specified in docs/RULES_ENGINE.md §3
 * and must stay identical to the JavaScript engine in frontend/ (checked by the shared fixtures).
 */
@Component
public class RulesEngine {

	private final JsonLogic jsonLogic = new JsonLogic();
	private final ObjectMapper objectMapper;

	public RulesEngine(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	/**
	 * Evaluates one rule.
	 *
	 * <p>A rule that reads a missing field is {@code UNKNOWN}. A rule that reads banded fields is
	 * evaluated at every corner of those bands: all true is {@code PASS}, all false is {@code FAIL},
	 * and a mix is {@code UNKNOWN} with the banded fields reported as needing a narrower answer.
	 *
	 * @param profile field name to value; a {@link Band} for ranged answers; absent or null for
	 *     "not answered / not sure"
	 */
	public RuleTrace evaluateRule(Rule rule, Map<String, ?> profile) {
		Set<String> vars = variablesOf(rule.jsonLogic());

		List<String> missing = vars.stream().filter(v -> profile.get(v) == null).toList();
		if (!missing.isEmpty()) {
			return new RuleTrace(rule.code(), RuleOutcome.UNKNOWN, rule.hard(), rule.confidence(), missing);
		}

		List<String> banded = vars.stream().filter(v -> profile.get(v) instanceof Band).toList();
		boolean anyTrue = false;
		boolean anyFalse = false;
		for (int corner = 0; corner < (1 << banded.size()); corner++) {
			Map<String, Object> data = new HashMap<>(profile);
			for (int i = 0; i < banded.size(); i++) {
				Band band = (Band) profile.get(banded.get(i));
				data.put(banded.get(i), (corner & (1 << i)) == 0 ? band.min() : band.max());
			}
			if (apply(rule, data)) {
				anyTrue = true;
			} else {
				anyFalse = true;
			}
		}

		if (anyTrue && anyFalse) {
			return new RuleTrace(rule.code(), RuleOutcome.UNKNOWN, rule.hard(), rule.confidence(), banded);
		}
		RuleOutcome outcome = anyTrue ? RuleOutcome.PASS : RuleOutcome.FAIL;
		return new RuleTrace(rule.code(), outcome, rule.hard(), rule.confidence(), List.of());
	}

	/**
	 * Evaluates all rules of a scheme and combines them:
	 *
	 * <ul>
	 *   <li>a hard rule fails and is not {@code UNCERTAIN} → {@code NOT_ELIGIBLE};
	 *   <li>else a hard rule is unknown, or an {@code UNCERTAIN} hard rule fails → {@code
	 *       NEED_MORE_INFO};
	 *   <li>else → {@code LIKELY_ELIGIBLE}.
	 * </ul>
	 *
	 * Soft rules never change the result.
	 */
	public SchemeEvaluation evaluateScheme(List<Rule> rules, Map<String, ?> profile) {
		List<RuleTrace> traces = new ArrayList<>(rules.size());
		for (Rule rule : rules) {
			traces.add(evaluateRule(rule, profile));
		}

		boolean definiteFail = false;
		boolean needInfo = false;
		Set<String> missing = new LinkedHashSet<>();
		for (RuleTrace trace : traces) {
			if (!trace.hard()) {
				continue;
			}
			switch (trace.outcome()) {
				case FAIL -> {
					if (trace.confidence() == Confidence.UNCERTAIN) {
						needInfo = true;
					} else {
						definiteFail = true;
					}
				}
				case UNKNOWN -> {
					needInfo = true;
					missing.addAll(trace.missingFields());
				}
				case PASS -> {}
			}
		}

		MatchResult result;
		if (definiteFail) {
			result = MatchResult.NOT_ELIGIBLE;
		} else if (needInfo) {
			result = MatchResult.NEED_MORE_INFO;
		} else {
			result = MatchResult.LIKELY_ELIGIBLE;
		}
		return new SchemeEvaluation(result, traces, new ArrayList<>(missing));
	}

	private boolean apply(Rule rule, Map<String, Object> data) {
		try {
			return JsonLogic.truthy(jsonLogic.apply(rule.jsonLogic(), data));
		} catch (JsonLogicException e) {
			throw new IllegalArgumentException("Rule " + rule.code() + " cannot be evaluated", e);
		}
	}

	/** Names of all profile fields a json-logic expression reads through {@code var}. */
	Set<String> variablesOf(String jsonLogicExpression) {
		Set<String> vars = new LinkedHashSet<>();
		collectVariables(objectMapper.readTree(jsonLogicExpression), vars);
		return vars;
	}

	private static void collectVariables(JsonNode node, Set<String> vars) {
		if (node.isArray()) {
			for (JsonNode child : node) {
				collectVariables(child, vars);
			}
		} else if (node.isObject()) {
			for (Map.Entry<String, JsonNode> entry : node.properties()) {
				if (entry.getKey().equals("var")) {
					JsonNode name = entry.getValue().isArray() ? entry.getValue().get(0) : entry.getValue();
					if (name != null && name.isString() && !name.asString().isEmpty()) {
						vars.add(name.asString());
					}
				} else {
					collectVariables(entry.getValue(), vars);
				}
			}
		}
	}
}
