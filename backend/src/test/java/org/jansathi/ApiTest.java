package org.jansathi;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** The HTTP API end to end, on the real content bundle (preview mode while nothing is published). */
@SpringBootTest
@AutoConfigureMockMvc
class ApiTest {

	private static final String ADMIN = "Bearer test-admin-token";

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> "jdbc:h2:mem:apitest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
		registry.add("jansathi.admin-token", () -> "test-admin-token");
	}

	@Autowired
	MockMvc mvc;

	private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

	@Test
	void listsSchemesWithTrustLabels() throws Exception {
		mvc.perform(get("/api/v1/schemes").param("lang", "hi"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.version").isString())
				.andExpect(jsonPath("$.schemes.length()").value(28))
				.andExpect(jsonPath("$.schemes[0].slug").isString())
				// Drafts are never presented as verified (kcc is under its second review).
				.andExpect(jsonPath("$.schemes[?(@.slug == 'kcc')].stale").value(org.hamcrest.Matchers.contains(true)))
				.andExpect(jsonPath("$.schemes[?(@.slug == 'kcc')].label").value(org.hamcrest.Matchers.contains("uncertain")))
				// Published after two independent reviews: sources verified, not stale.
				.andExpect(jsonPath("$.schemes[?(@.slug == 'pm-kisan')].stale").value(org.hamcrest.Matchers.contains(false)));
	}

	@Test
	void schemeDetailCarriesSources() throws Exception {
		mvc.perform(get("/api/v1/schemes/atal-pension"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.scheme.sources[0].url").isString())
				.andExpect(jsonPath("$.scheme.sources[0].fetched_at").exists())
				.andExpect(jsonPath("$.scheme.sources[0]").value(org.hamcrest.Matchers.hasKey("verified_at")));
	}

	@Test
	void unknownSchemeIsProblemJson() throws Exception {
		mvc.perform(get("/api/v1/schemes/no-such-scheme"))
				.andExpect(status().isNotFound())
				.andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("problem+json")));
	}

	@Test
	void matchReturnsThreeStateResultsAndNextQuestion() throws Exception {
		mvc.perform(post("/api/v1/match").contentType(JSON)
						.content("{\"profile\": {\"age\": {\"min\": 60, \"max\": 69}}, \"as_of\": \"2026-10-25\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ruleset_version").isString())
				.andExpect(jsonPath("$.next_question").isString())
				.andExpect(jsonPath("$.results[?(@.scheme == 'atal-pension')].result").value("NOT_ELIGIBLE"))
				.andExpect(jsonPath("$.results[0].rules[0].missing_fields").isArray());
	}

	@Test
	void matchRejectsFieldsTheRulesDoNotUse() throws Exception {
		mvc.perform(post("/api/v1/match").contentType(JSON).content("{\"profile\": {\"aadhaar_number\": \"1234\"}}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("Unknown profile field: aadhaar_number"));
	}

	@Test
	void bundleIsCacheableByVersion() throws Exception {
		String etag = mvc.perform(get("/api/v1/rules/bundle"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.fields.fields.age.type").value("band"))
				.andExpect(header().string("X-JanSathi-Preview", "true"))
				.andReturn().getResponse().getHeader("ETag");
		mvc.perform(get("/api/v1/rules/bundle").header("If-None-Match", etag)).andExpect(status().isNotModified());
	}

	@Test
	void checklistDeduplicatesDocuments() throws Exception {
		mvc.perform(post("/api/v1/checklist").contentType(JSON)
						.content("{\"schemes\": [\"atal-pension\", \"pm-kisan\"], \"lang\": \"hi\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[?(@.code == 'bank_account')].schemes.length()").value(2))
				.andExpect(jsonPath("$.items[0].mandatory").value(true))
				.andExpect(jsonPath("$.items[0].name.lang").value("hi"));
	}

	@Test
	void searchFindsSchemesByWord() throws Exception {
		mvc.perform(get("/api/v1/search").param("q", "pension"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.schemes[?(@.slug == 'atal-pension')]").exists());
	}

	@Test
	void askReturnsCitedPassagesOnly() throws Exception {
		mvc.perform(post("/api/v1/ask").contentType(JSON).content("{\"question\": \"widow pension age\", \"lang\": \"en\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("sources_only"))
				.andExpect(jsonPath("$.answer").doesNotExist())
				.andExpect(jsonPath("$.passages[0].id").value("p1"))
				.andExpect(jsonPath("$.passages[0].source.url").isString())
				.andExpect(jsonPath("$.label").value("uncertain"));
	}

	@Test
	void askWithoutMatchSaysNoSource() throws Exception {
		mvc.perform(post("/api/v1/ask").contentType(JSON).content("{\"question\": \"cricket score today\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("not_found"))
				.andExpect(jsonPath("$.passages.length()").value(0));
	}

	@Test
	void reportsGoToTheModerationQueueWithoutPersonalNumbers() throws Exception {
		String id = mvc.perform(post("/api/v1/reports").contentType(JSON)
						.content("{\"scheme\": \"atal-pension\", \"reason\": \"outdated\", \"note\": \"my aadhaar 1234 5678 9012 page changed\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

		mvc.perform(get("/api/v1/admin/reports").param("status", "open").header("Authorization", ADMIN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.id == '" + id + "')].note").value(org.hamcrest.Matchers.contains("my aadhaar [number removed] page changed")));

		mvc.perform(post("/api/v1/admin/reports/" + id + "/resolve").header("Authorization", ADMIN).contentType(JSON)
						.content("{\"status\": \"resolved\", \"resolution\": \"Source re-checked\"}"))
				.andExpect(status().isOk());
		mvc.perform(post("/api/v1/admin/reports/" + id + "/resolve").header("Authorization", ADMIN).contentType(JSON)
						.content("{\"status\": \"resolved\"}"))
				.andExpect(status().isNotFound());
	}

	@Test
	void adminApiNeedsTheToken() throws Exception {
		mvc.perform(get("/api/v1/admin/reports")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/admin/reports").header("Authorization", "Bearer wrong")).andExpect(status().isUnauthorized());
	}

	@Test
	void securityHeadersAreSet() throws Exception {
		mvc.perform(get("/api/v1/categories"))
				.andExpect(status().isOk())
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(jsonPath("$.categories.pension").isNumber());
	}

	@Test
	void openApiIsPublished() throws Exception {
		mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andExpect(jsonPath("$.paths['/api/v1/match']").exists());
	}
}
