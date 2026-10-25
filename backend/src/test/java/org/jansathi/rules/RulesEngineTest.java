package org.jansathi.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the shared fixtures in {@code rules/} (docs/RULES_ENGINE.md §9.7) through the Java engine.
 * The JavaScript engine runs the same files in {@code tools/test-rules.mjs}, so passing both keeps
 * the two engines identical.
 */
class RulesEngineTest {

	private static final ObjectMapper JSON = JsonMapper.builder().build();
	private static final Path ROOT = Path.of(System.getProperty("jansathi.repoRoot", ".."));

	private final RulesEngine engine = new RulesEngine();
	private final JsonNode fieldsJson = read(ROOT.resolve("content/fields.json"));
	private final FieldCatalog fields = new FieldCatalog(fieldsJson);

	private static JsonNode read(Path file) {
		try {
			return JSON.readTree(Files.readString(file));
		} catch (IOException e) {
			throw new IllegalStateException(file + " cannot be read", e);
		}
	}

	private static Set<String> skipped(JsonNode c) {
		Set<String> out = new HashSet<>();
		c.path("skipped").forEach(x -> out.add(x.asString()));
		return out;
	}

	private static LocalDate asOf(JsonNode c) {
		return c.hasNonNull("as_of") ? LocalDate.parse(c.get("as_of").asString()) : null;
	}

	private void checkScheme(JsonNode scheme, JsonNode c) {
		SchemeResult got = engine.evaluateScheme(scheme, c.path("profile"), fields, asOf(c));
		JsonNode expect = c.get("expect");
		if (expect.has("result")) {
			assertThat(got.result().name()).as("result").isEqualTo(expect.get("result").asString());
		}
		if (expect.has("missing_fields")) {
			List<String> want = new ArrayList<>();
			expect.get("missing_fields").forEach(x -> want.add(x.asString()));
			assertThat(got.missingFields()).as("missing_fields").containsExactlyElementsOf(want);
		}
		if (expect.has("stale")) {
			assertThat(got.stale()).as("stale").isEqualTo(expect.get("stale").asBoolean());
		}
		if (expect.has("label")) {
			assertThat(got.label().json()).as("label").isEqualTo(expect.get("label").asString());
		}
		for (Map.Entry<String, JsonNode> e : expect.path("rules").properties()) {
			RuleTrace trace = got.rules().stream().filter(r -> r.code().equals(e.getKey())).findFirst().orElseThrow();
			assertThat(trace.outcome().json()).as("rule " + e.getKey()).isEqualTo(e.getValue().asString());
		}
	}

	private void checkMulti(JsonNode c) {
		List<JsonNode> schemes = new ArrayList<>();
		c.get("schemes").forEach(schemes::add);
		MatchResponse got = engine.match("test", schemes, c.path("profile"), fields, skipped(c), asOf(c));
		JsonNode expect = c.get("expect");
		if (expect.has("next_question")) {
			String want = expect.get("next_question").isNull() ? null : expect.get("next_question").asString();
			assertThat(got.nextQuestion()).as("next_question").isEqualTo(want);
		}
		for (Map.Entry<String, JsonNode> e : expect.path("results").properties()) {
			SchemeResult r = got.results().stream().filter(x -> x.scheme().equals(e.getKey())).findFirst().orElseThrow();
			assertThat(r.result().name()).as("result for " + e.getKey()).isEqualTo(e.getValue().asString());
		}
	}

	@TestFactory
	Stream<DynamicTest> engineFixtures() {
		List<DynamicTest> tests = new ArrayList<>();
		for (JsonNode c : read(ROOT.resolve("rules/_engine.cases.json"))) {
			tests.add(DynamicTest.dynamicTest("engine: " + c.get("name").asString(),
					() -> {
						if (c.has("schemes")) {
							checkMulti(c);
						} else {
							checkScheme(c.get("scheme"), c);
						}
					}));
		}
		return tests.stream();
	}

	@TestFactory
	Stream<DynamicTest> schemeFixtures() throws IOException {
		List<DynamicTest> tests = new ArrayList<>();
		try (Stream<Path> files = Files.list(ROOT.resolve("content/schemes"))) {
			for (Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
				JsonNode scheme = read(file);
				String slug = file.getFileName().toString().replace(".json", "");
				tests.add(DynamicTest.dynamicTest(slug + ": rules are valid", () -> {
					for (JsonNode rule : scheme.path("rules")) {
						assertThat(RulesEngine.validate(rule.get("logic"), fields)).as("rule " + rule.path("code")).isEmpty();
					}
				}));
				Path cases = ROOT.resolve("rules/" + slug + ".cases.json");
				tests.add(DynamicTest.dynamicTest(slug + ": has fixtures", () -> assertThat(cases).exists()));
				if (Files.exists(cases)) {
					for (JsonNode c : read(cases)) {
						tests.add(DynamicTest.dynamicTest(slug + ": " + c.get("name").asString(), () -> checkScheme(scheme, c)));
					}
				}
			}
		}
		return tests.stream();
	}

	@Test
	void nestedVariablesAreCollectedInOrder() {
		JsonNode logic = JSON.readTree("{\"or\": [{\">=\": [{\"var\": \"age\"}, 70]}, {\"and\": [{\"var\": [\"is_bpl\", false]}, {\"!\": {\"var\": \"x\"}}]}]}");

		assertThat(RulesEngine.referencedFields(logic)).containsExactly("age", "is_bpl", "x");
	}

	@Test
	void unknownOperatorFailsValidation() {
		JsonNode logic = JSON.readTree("{\"merge\": [1, 2]}");

		assertThat(RulesEngine.validate(logic, fields)).containsExactly("operator not allowed: merge");
	}
}
