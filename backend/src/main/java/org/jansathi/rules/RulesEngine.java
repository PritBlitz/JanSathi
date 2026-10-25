package org.jansathi.rules;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Deterministic three-state eligibility evaluation. This is the only component that decides match
 * results; the assistant only explains them.
 *
 * <p>Implements docs/RULES_ENGINE.md §9 exactly and must give the same results as the JavaScript
 * engine in {@code frontend/src/lib/rules.js} on every fixture in {@code rules/}. Stock json-logic
 * has no "unknown" value, so the §9.2 subset is evaluated here directly (Kleene three-valued
 * logic). Rules and profiles are read as JSON trees, the same documents the frontend receives.
 */
@Component
public class RulesEngine {

	/** The third truth value: a rule read a field the user has not answered. */
	private static final Object U = new Object() {
		@Override
		public String toString() {
			return "U";
		}
	};

	/** JSON {@code null} as a value, distinct from {@link #U}. */
	private static final Object NULL = new Object() {
		@Override
		public String toString() {
			return "null";
		}
	};

	public static final List<String> OPERATORS = List.of("var", "==", "!=", "<", "<=", ">", ">=", "in", "and", "or", "!");
	private static final Set<String> COMPARE = Set.of("<", "<=", ">", ">=");
	private static final Set<String> BINARY = Set.of("==", "!=", "<", "<=", ">", ">=", "in");
	private static final int DEFAULT_REVERIFY_DAYS = 180;

	// ---------- rule structure ----------

	private static String opOf(JsonNode node) {
		if (node == null || !node.isObject()) {
			return null;
		}
		if (node.size() != 1) {
			throw new RuleException("rule node must have exactly one operator: " + node);
		}
		return node.properties().iterator().next().getKey();
	}

	private static List<JsonNode> argsOf(JsonNode node, String op) {
		JsonNode a = node.get(op);
		List<JsonNode> args = new ArrayList<>();
		if (a.isArray()) {
			a.forEach(args::add);
		} else {
			args.add(a);
		}
		return args;
	}

	/** Field names a rule reads through {@code {"var": …}}, in first-seen order. */
	public static List<String> referencedFields(JsonNode logic) {
		Set<String> out = new LinkedHashSet<>();
		collectFields(logic, out);
		return List.copyOf(out);
	}

	private static void collectFields(JsonNode logic, Set<String> out) {
		String op = opOf(logic);
		if (op == null) {
			if (logic != null && logic.isArray()) {
				logic.forEach(x -> collectFields(x, out));
			}
			return;
		}
		List<JsonNode> args = argsOf(logic, op);
		if (op.equals("var")) {
			if (!args.getFirst().isString()) {
				throw new RuleException("var takes a field name");
			}
			out.add(args.getFirst().asString());
			return;
		}
		args.forEach(x -> collectFields(x, out));
	}

	/**
	 * Checks that a rule uses only the §9.2 operators, only known fields, and only comparison
	 * operators on band fields, so corner evaluation is sound. Returns the problems found.
	 */
	public static List<String> validate(JsonNode logic, FieldCatalog fields) {
		List<String> errors = new ArrayList<>();
		try {
			validateNode(logic, null, fields, errors);
		} catch (RuleException e) {
			errors.add(e.getMessage());
		}
		return errors;
	}

	private static void validateNode(JsonNode node, String parentOp, FieldCatalog fields, List<String> errors) {
		String op = opOf(node);
		if (op == null) {
			if (node != null && node.isArray()) {
				node.forEach(x -> validateNode(x, parentOp, fields, errors));
			}
			return;
		}
		if (!OPERATORS.contains(op)) {
			errors.add("operator not allowed: " + op);
			return;
		}
		List<JsonNode> args = argsOf(node, op);
		if (op.equals("var")) {
			String name = args.getFirst().asString("");
			if (!fields.isKnown(name)) {
				errors.add("unknown field: " + name);
			} else if (fields.isBand(name) && !COMPARE.contains(parentOp)) {
				errors.add("band field " + name + " may only be used with <, <=, >, >=");
			}
			return;
		}
		if (BINARY.contains(op) && args.size() != 2) {
			errors.add(op + " takes exactly 2 operands");
		}
		if (op.equals("in") && (args.size() < 2 || !args.get(1).isArray())) {
			errors.add("in needs a literal array as its second operand");
		}
		if (op.equals("!") && args.size() != 1) {
			errors.add("! takes exactly 1 operand");
		}
		args.forEach(x -> validateNode(x, op, fields, errors));
	}

	// ---------- §9.2 value logic ----------

	/** Converts a JSON literal to a plain value: Double, String, Boolean, List or {@link #NULL}. */
	private static Object literal(JsonNode node) {
		if (node == null || node.isNull() || node.isMissingNode()) {
			return NULL;
		}
		if (node.isNumber()) {
			return node.doubleValue();
		}
		if (node.isBoolean()) {
			return node.booleanValue();
		}
		if (node.isString()) {
			return node.asString();
		}
		if (node.isArray()) {
			List<Object> list = new ArrayList<>();
			node.forEach(x -> list.add(literal(x)));
			return list;
		}
		return node; // an object literal: only ever compared by identity, as in JS
	}

	/** JavaScript strict equality ({@code ===}) on plain values. */
	private static boolean strictEquals(Object a, Object b) {
		if (a instanceof Double x && b instanceof Double y) {
			return x.doubleValue() == y.doubleValue();
		}
		if (a instanceof String || a instanceof Boolean) {
			return a.equals(b);
		}
		return a == b;
	}

	/** JavaScript {@code Array.prototype.includes} (SameValueZero). */
	private static boolean includes(List<?> list, Object x) {
		for (Object item : list) {
			if (strictEquals(item, x) || (item instanceof Double d && x instanceof Double e && d.isNaN() && e.isNaN())) {
				return true;
			}
		}
		return false;
	}

	private static boolean truthy(Object v) {
		if (v == NULL) {
			return false;
		}
		if (v instanceof Boolean b) {
			return b;
		}
		if (v instanceof Double d) {
			return d != 0 && !d.isNaN();
		}
		if (v instanceof String s) {
			return !s.isEmpty();
		}
		return true;
	}

	/** Evaluates logic against plain values. Returns Boolean, {@link #U}, or a literal value. */
	private static Object eval(JsonNode node, Map<String, Object> values) {
		String op = opOf(node);
		if (op == null) {
			return literal(node);
		}
		List<JsonNode> args = argsOf(node, op);
		switch (op) {
			case "var" -> {
				String name = args.getFirst().asString();
				return values.containsKey(name) ? values.get(name) : U;
			}
			case "==", "!=", "<", "<=", ">", ">=" -> {
				Object a = eval(args.get(0), values);
				Object b = eval(args.get(1), values);
				if (a == U || b == U) {
					return U;
				}
				if (op.equals("==")) {
					return strictEquals(a, b);
				}
				if (op.equals("!=")) {
					return !strictEquals(a, b);
				}
				if (!(a instanceof Double x) || !(b instanceof Double y)) {
					return false;
				}
				return switch (op) {
					case "<" -> x < y;
					case "<=" -> x <= y;
					case ">" -> x > y;
					default -> x >= y;
				};
			}
			case "in" -> {
				Object x = eval(args.get(0), values);
				if (x == U) {
					return U;
				}
				return args.size() > 1 && args.get(1).isArray() && includes((List<?>) literal(args.get(1)), x);
			}
			case "!" -> {
				Object x = eval(args.getFirst(), values);
				return x == U ? U : !truthy(x);
			}
			case "and" -> {
				boolean unknown = false;
				for (JsonNode a : args) {
					Object v = eval(a, values);
					if (Boolean.FALSE.equals(v)) {
						return false;
					}
					if (v == U) {
						unknown = true;
					}
				}
				return unknown ? U : true;
			}
			case "or" -> {
				boolean unknown = false;
				for (JsonNode a : args) {
					Object v = eval(a, values);
					if (Boolean.TRUE.equals(v)) {
						return true;
					}
					if (v == U) {
						unknown = true;
					}
				}
				return unknown ? U : false;
			}
			default -> throw new RuleException("operator not allowed: " + op);
		}
	}

	// ---------- §9.3 rule outcome ----------

	private record Band(double min, double max) {}

	/** A band profile value: {@code {min, max}} or an exact number n, read as {n, n}. Null if neither. */
	private static Band toBand(JsonNode v) {
		if (v == null) {
			return null;
		}
		if (v.isNumber()) {
			return new Band(v.doubleValue(), v.doubleValue());
		}
		if (v.isObject() && v.path("min").isNumber() && v.path("max").isNumber()) {
			return new Band(v.get("min").doubleValue(), v.get("max").doubleValue());
		}
		return null;
	}

	/** The outcome of one rule, with the fields still needed when it is unknown. */
	public record RuleEvaluation(RuleOutcome outcome, List<String> missingFields) {}

	/** §9.3: evaluates one rule at every corner of the straddling bands it reads. */
	public RuleEvaluation evaluateRule(JsonNode logic, JsonNode profile, FieldCatalog fields) {
		Map<String, Object> base = new HashMap<>();
		List<String> absent = new ArrayList<>();
		List<String> straddling = new ArrayList<>();
		Map<String, Band> bands = new HashMap<>();
		for (String name : referencedFields(logic)) {
			if (!profile.has(name)) {
				absent.add(name);
				continue;
			}
			JsonNode value = profile.get(name);
			if (fields.isBand(name)) {
				Band band = toBand(value);
				if (band == null) {
					absent.add(name);
				} else if (band.min() == band.max()) {
					base.put(name, band.min());
				} else {
					straddling.add(name);
					bands.put(name, band);
				}
			} else {
				base.put(name, literal(value));
			}
		}
		List<String> b = fields.sorted(straddling);

		// Corner k picks max for b[i] when bit (n - 1 - i) of k is set.
		int n = b.size();
		Object[] results = new Object[1 << n];
		for (int k = 0; k < results.length; k++) {
			Map<String, Object> values = new HashMap<>(base);
			for (int i = 0; i < n; i++) {
				Band band = bands.get(b.get(i));
				values.put(b.get(i), ((k >> (n - 1 - i)) & 1) == 1 ? band.max() : band.min());
			}
			results[k] = eval(logic, values);
		}

		boolean allTrue = true;
		boolean allFalse = true;
		for (Object r : results) {
			allTrue &= Boolean.TRUE.equals(r);
			allFalse &= Boolean.FALSE.equals(r);
		}
		if (allTrue) {
			return new RuleEvaluation(RuleOutcome.PASS, List.of());
		}
		if (allFalse) {
			return new RuleEvaluation(RuleOutcome.FAIL, List.of());
		}

		List<String> missing = new ArrayList<>(absent);
		for (int i = 0; i < n; i++) {
			int bit = 1 << (n - 1 - i);
			for (int k = 0; k < results.length; k++) {
				if (!Objects.equals(results[k], results[k ^ bit])) {
					missing.add(b.get(i));
					break;
				}
			}
		}
		return new RuleEvaluation(RuleOutcome.UNKNOWN, fields.sorted(missing));
	}

	// ---------- §9.4–9.5 scheme result ----------

	/** §9.4–9.5: evaluates every rule of a scheme and combines the hard ones. */
	public SchemeResult evaluateScheme(JsonNode scheme, JsonNode profile, FieldCatalog fields, LocalDate asOf) {
		Map<String, JsonNode> sources = new HashMap<>();
		scheme.path("sources").forEach(s -> sources.put(s.path("id").asString(""), s));

		List<RuleTrace> traces = new ArrayList<>();
		for (JsonNode rule : scheme.path("rules")) {
			RuleEvaluation e = evaluateRule(rule.get("logic"), profile, fields);
			traces.add(new RuleTrace(
					rule.path("code").asString(""),
					e.outcome(),
					rule.path("hard").asBoolean(false),
					Confidence.parse(rule.path("confidence").asString(null)),
					rule.path("source").asString(null),
					e.missingFields()));
		}
		List<RuleTrace> hard = traces.stream().filter(RuleTrace::hard).toList();

		MatchResult result;
		if (hard.stream().anyMatch(r -> r.outcome() == RuleOutcome.FAIL && r.confidence() != Confidence.UNCERTAIN)) {
			result = MatchResult.NOT_ELIGIBLE;
		} else if (hard.stream().anyMatch(r -> r.outcome() == RuleOutcome.UNKNOWN
				|| (r.outcome() == RuleOutcome.FAIL && r.confidence() == Confidence.UNCERTAIN))) {
			result = MatchResult.NEED_MORE_INFO;
		} else {
			result = MatchResult.LIKELY_ELIGIBLE;
		}

		Set<String> missing = new LinkedHashSet<>();
		hard.stream().filter(r -> r.outcome() == RuleOutcome.UNKNOWN).forEach(r -> missing.addAll(r.missingFields()));

		boolean stale = !"published".equals(scheme.path("status").asString(""));
		int reverify = scheme.path("reverify_days").isNumber() ? scheme.get("reverify_days").intValue() : DEFAULT_REVERIFY_DAYS;
		for (RuleTrace r : hard) {
			JsonNode src = sources.get(r.source());
			String verifiedAt = src == null ? null : src.path("verified_at").asString(null);
			if (verifiedAt == null || verifiedAt.isEmpty()) {
				stale = true;
				continue;
			}
			if (asOf != null && ChronoUnit.DAYS.between(LocalDate.parse(verifiedAt.substring(0, 10)), asOf) > reverify) {
				stale = true;
			}
		}

		Confidence label = Confidence.CONFIRMED;
		for (RuleTrace r : hard) {
			if (r.confidence().ordinal() < label.ordinal()) {
				label = r.confidence();
			}
		}
		if (stale) {
			label = Confidence.UNCERTAIN;
		}

		return new SchemeResult(scheme.path("slug").asString(""), result, label, stale, new ArrayList<>(missing), traces);
	}

	// ---------- §9.6 next question ----------

	/** §9.6: the field that would resolve the most NEED_MORE_INFO schemes, or null. */
	public String nextQuestion(List<SchemeResult> results, FieldCatalog fields, Set<String> skipped) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (SchemeResult r : results) {
			if (r.result() != MatchResult.NEED_MORE_INFO) {
				continue;
			}
			for (String f : r.missingFields()) {
				if (!skipped.contains(f)) {
					counts.merge(f, 1, Integer::sum);
				}
			}
		}
		String best = null;
		int bestCount = 0;
		for (String f : fields.sorted(counts.keySet())) {
			if (counts.get(f) > bestCount) {
				best = f;
				bestCount = counts.get(f);
			}
		}
		return best;
	}

	/** Matches a profile against the given schemes. Returns the {@code POST /api/v1/match} shape. */
	public MatchResponse match(
			String rulesetVersion, List<JsonNode> schemes, JsonNode profile, FieldCatalog fields, Set<String> skipped, LocalDate asOf) {
		List<SchemeResult> results = schemes.stream().map(s -> evaluateScheme(s, profile, fields, asOf)).toList();
		return new MatchResponse(rulesetVersion, nextQuestion(results, fields, skipped), results);
	}
}
