package org.jansathi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Sign-in, the saved household, sharing with a helper, the helper portal and the audit log. */
@SpringBootTest
@AutoConfigureMockMvc
class AccountsAndVolunteersTest {

	private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
	private static final String ADMIN = "Bearer test-admin-token";
	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> "jdbc:h2:mem:accounts;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
		registry.add("jansathi.admin-token", () -> "test-admin-token");
		registry.add("jansathi.secret", () -> "test-secret-not-for-production");
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	private JsonNode body(org.springframework.test.web.servlet.ResultActions r) throws Exception {
		return MAPPER.readTree(r.andReturn().getResponse().getContentAsString());
	}

	/**
	 * Signs in with the development code and returns the bearer header. Each number comes from its
	 * own address, so the per-address code limit does not depend on how many tests there are.
	 */
	private String signIn(String phone) throws Exception {
		String digits = phone.replaceAll("\\D", "");
		String address = "10.0.0." + digits.substring(digits.length() - 2);
		String code = body(mvc.perform(post("/api/v1/auth/code").contentType(JSON).content("{\"phone\": \"" + phone + "\"}")
						.with(r -> { r.setRemoteAddr(address); return r; }))
				.andExpect(status().isOk())).path("dev_code").asString();
		assertThat(code).hasSize(6);
		String token = body(mvc.perform(post("/api/v1/auth/verify").contentType(JSON)
				.content("{\"phone\": \"" + phone + "\", \"code\": \"" + code + "\"}")).andExpect(status().isOk())).path("token").asString();
		return "Bearer " + token;
	}

	@Test
	void phoneIsNeverStoredOnlyItsHashAndLastDigits() throws Exception {
		String me = signIn("+91 98765 00001");

		mvc.perform(get("/api/v1/me").header("Authorization", me))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.phone_last4").value("0001"))
				.andExpect(jsonPath("$.role").value("user"));
		String stored = jdbc.sql("SELECT phone_hash FROM app_user WHERE phone_last4 = '0001'").query(String.class).single();
		assertThat(stored).doesNotContain("9876500001").hasSize(64);
	}

	@Test
	void wrongCodesAreLimitedAndNonIndianNumbersRejected() throws Exception {
		mvc.perform(post("/api/v1/auth/code").contentType(JSON).content("{\"phone\": \"12345\"}")).andExpect(status().isBadRequest());
		mvc.perform(post("/api/v1/auth/code").contentType(JSON).content("{\"phone\": \"9876500002\"}")).andExpect(status().isOk());
		for (int i = 0; i < 5; i++) {
			mvc.perform(post("/api/v1/auth/verify").contentType(JSON).content("{\"phone\": \"9876500002\", \"code\": \"000000\"}"))
					.andExpect(status().isBadRequest());
		}
		mvc.perform(post("/api/v1/auth/verify").contentType(JSON).content("{\"phone\": \"9876500002\", \"code\": \"000000\"}"))
				.andExpect(jsonPath("$.detail").value("Too many wrong codes. Ask for a new one."));
	}

	@Test
	void signedOutRequestsAreRefused() throws Exception {
		mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/me").header("Authorization", "Bearer nonsense")).andExpect(status().isUnauthorized());
	}

	@Test
	void householdIsSavedEncryptedAndOnlyKnownFieldsAreAccepted() throws Exception {
		String me = signIn("9876500003");
		String household = "{\"members\": [{\"id\": \"me\", \"name\": null, \"profile\": {\"age\": {\"min\": 60, \"max\": 69}}, \"skipped\": [], \"history\": []}],"
				+ " \"active\": \"me\", \"checklist\": {\"schemes\": [\"ignoaps\"], \"have\": {\"aadhaar\": true}}}";
		mvc.perform(put("/api/v1/me/household").header("Authorization", me).contentType(JSON).content(household)).andExpect(status().isOk());
		mvc.perform(get("/api/v1/me/household").header("Authorization", me))
				.andExpect(jsonPath("$.members[0].profile.age.min").value(60))
				.andExpect(jsonPath("$.checklist.schemes[0]").value("ignoaps"));
		String enc = jdbc.sql("SELECT payload_enc FROM user_data").query(String.class).list().getLast();
		assertThat(enc).doesNotContain("ignoaps").doesNotContain("\"age\"");

		mvc.perform(put("/api/v1/me/household").header("Authorization", me).contentType(JSON)
						.content("{\"members\": [{\"id\": \"me\", \"profile\": {\"aadhaar_number\": \"123412341234\"}}]}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void helperSeesOnlyWhatWasSharedAndEveryLookIsAudited() throws Exception {
		String person = signIn("9876500004");
		mvc.perform(put("/api/v1/me/household").header("Authorization", person).contentType(JSON)
						.content("{\"members\": [{\"id\": \"me\", \"name\": \"Asha\", \"profile\": {\"age\": 65, \"is_bpl\": true}, \"skipped\": [], \"history\": []}], \"active\": \"me\", \"checklist\": {\"schemes\": [], \"have\": {}}}"))
				.andExpect(status().isOk());

		mvc.perform(post("/api/v1/admin/volunteers").header("Authorization", ADMIN).contentType(JSON)
						.content("{\"phone\": \"9876500005\", \"name\": \"Ravi\", \"organisation\": \"Gram CSC\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.role").value("volunteer"));
		String helper = signIn("9876500005");

		// An ordinary user cannot use the portal.
		mvc.perform(get("/api/v1/volunteer").header("Authorization", person)).andExpect(status().isForbidden());

		JsonNode share = body(mvc.perform(post("/api/v1/me/shares").header("Authorization", person).contentType(JSON)
				.content("{\"scopes\": [\"profile\"], \"hours\": 2}")).andExpect(status().isOk()));
		String code = share.path("code").asString();
		assertThat(code).hasSize(6);

		String caseId = body(mvc.perform(post("/api/v1/volunteer/join").header("Authorization", helper).contentType(JSON)
				.content("{\"code\": \"" + code.toLowerCase() + "\"}")).andExpect(status().isOk())).path("id").asString();

		mvc.perform(get("/api/v1/volunteer/cases/" + caseId).header("Authorization", helper))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.members[0].name").value("Asha"))
				.andExpect(jsonPath("$.members[0].results[?(@.scheme == 'ignoaps')].result").value(org.hamcrest.Matchers.contains("LIKELY_ELIGIBLE")))
				.andExpect(jsonPath("$.checklist").doesNotExist())
				.andExpect(jsonPath("$.conversation.length()").value(0));

		mvc.perform(post("/api/v1/volunteer/cases/" + caseId + "/notes").header("Authorization", helper).contentType(JSON)
				.content("{\"text\": \"Bring your BPL card to the block office. Call me on 98765 43210\"}"))
				.andExpect(status().isOk());

		mvc.perform(get("/api/v1/me/notes").header("Authorization", person))
				.andExpect(jsonPath("$[0].helper").value("Ravi (Gram CSC)"))
				.andExpect(jsonPath("$[0].text").value("Bring your BPL card to the block office. Call me on [number removed]"));
		mvc.perform(get("/api/v1/me/audit").header("Authorization", person))
				.andExpect(jsonPath("$[?(@.action == 'helper_viewed')].actor").value(org.hamcrest.Matchers.contains("Ravi (Gram CSC)")))
				.andExpect(jsonPath("$[?(@.action == 'helper_joined')]").exists());

		// The person ends it; the helper loses access at once.
		mvc.perform(delete("/api/v1/me/shares/" + share.path("id").asString()).header("Authorization", person)).andExpect(status().isOk());
		mvc.perform(get("/api/v1/volunteer/cases/" + caseId).header("Authorization", helper)).andExpect(status().isForbidden());
	}

	@Test
	void helperWrongShareCodesAreLimited() throws Exception {
		mvc.perform(post("/api/v1/admin/volunteers").header("Authorization", ADMIN).contentType(JSON)
				.content("{\"phone\": \"9876500010\", \"name\": \"Kiran\"}")).andExpect(status().isOk());
		String helper = signIn("9876500010");
		for (int i = 0; i < 10; i++) {
			mvc.perform(post("/api/v1/volunteer/join").header("Authorization", helper).contentType(JSON)
					.content("{\"code\": \"ZZZZZ" + i + "\"}")).andExpect(status().isNotFound());
		}
		mvc.perform(post("/api/v1/volunteer/join").header("Authorization", helper).contentType(JSON)
				.content("{\"code\": \"ZZZZZZ\"}")).andExpect(status().isTooManyRequests());
	}

	@Test
	void helpRequestsQueueWithoutPersonalDetails() throws Exception {
		String person = signIn("9876500006");
		mvc.perform(post("/api/v1/admin/volunteers").header("Authorization", ADMIN).contentType(JSON)
				.content("{\"phone\": \"9876500007\", \"name\": \"Meena\"}")).andExpect(status().isOk());
		String helper = signIn("9876500007");

		String id = body(mvc.perform(post("/api/v1/me/shares").header("Authorization", person).contentType(JSON)
				.content("{\"help_request\": true, \"scopes\": [\"profile\", \"checklist\"], \"lang\": \"hi\"}"))).path("id").asString();

		JsonNode dash = body(mvc.perform(get("/api/v1/volunteer").header("Authorization", helper)).andExpect(status().isOk()));
		JsonNode request = null;
		for (JsonNode r : dash.path("open_requests")) {
			if (r.path("id").asString().equals(id)) {
				request = r;
			}
		}
		assertThat(request).isNotNull();
		assertThat(request.path("lang").asString()).isEqualTo("hi");
		assertThat(request.has("phone_last4")).isFalse();

		mvc.perform(post("/api/v1/volunteer/requests/" + id + "/pick-up").header("Authorization", helper)).andExpect(status().isOk());
		mvc.perform(get("/api/v1/volunteer/cases/" + id).header("Authorization", helper))
				.andExpect(status().isOk()).andExpect(jsonPath("$.checklist.schemes").isArray());
	}

	@Test
	void guideWithoutModelStepsThroughTheQuestions() throws Exception {
		String me = signIn("9876500008");
		mvc.perform(get("/api/v1/guide/status")).andExpect(jsonPath("$.ai").value(false));

		JsonNode first = body(mvc.perform(post("/api/v1/guide/messages").header("Authorization", me).contentType(JSON)
				.content("{\"lang\": \"hi\"}")).andExpect(status().isOk()));
		assertThat(first.path("fallback").asString()).isEqualTo("no_model");
		assertThat(first.path("question").path("field").asString()).isEqualTo("age");
		assertThat(first.path("question").path("question").asString()).isNotBlank().isNotEqualTo("q.age");

		JsonNode second = body(mvc.perform(post("/api/v1/guide/messages").header("Authorization", me).contentType(JSON)
				.content("{\"answer\": {\"field\": \"age\", \"value\": \"60_69\"}, \"lang\": \"en\"}")).andExpect(status().isOk()));
		assertThat(second.path("recorded").get(0).path("field").asString()).isEqualTo("age");
		assertThat(second.path("question").path("field").asString()).isNotEqualTo("age");
		assertThat(second.path("household").path("members").get(0).path("profile").path("age").path("min").asInt()).isEqualTo(60);

		mvc.perform(post("/api/v1/guide/messages").header("Authorization", me).contentType(JSON)
						.content("{\"answer\": {\"field\": \"age\", \"value\": \"not_an_option\"}}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void deletingTheAccountRemovesEverything() throws Exception {
		String me = signIn("9876500009");
		mvc.perform(post("/api/v1/me/shares").header("Authorization", me).contentType(JSON).content("{}")).andExpect(status().isOk());
		mvc.perform(delete("/api/v1/me").header("Authorization", me)).andExpect(status().isOk());
		mvc.perform(get("/api/v1/me").header("Authorization", me)).andExpect(status().isUnauthorized());
		assertThat(jdbc.sql("SELECT COUNT(*) FROM app_user WHERE phone_last4 = '0009'").query(Integer.class).single()).isZero();
	}
}
