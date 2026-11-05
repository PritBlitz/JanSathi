package org.jansathi.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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

/** Staff accounts, roles, the admin audit, the moderation queues and the status page. */
@SpringBootTest
@AutoConfigureMockMvc
class AdminApiTest {

	private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
	private static final String BOOTSTRAP = "Bearer test-admin-token";
	private static final String PASSWORD = "correct horse battery";
	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> "jdbc:h2:mem:admin;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
		registry.add("jansathi.admin-token", () -> "test-admin-token");
		registry.add("jansathi.secret", () -> "test-secret-not-for-production");
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	private JsonNode body(org.springframework.test.web.servlet.ResultActions r) throws Exception {
		String s = r.andReturn().getResponse().getContentAsString();
		return MAPPER.readTree(s.isEmpty() ? "{}" : s);
	}

	private String signInPerson(String phone) throws Exception {
		String code = body(mvc.perform(post("/api/v1/auth/code").contentType(JSON).content("{\"phone\": \"" + phone + "\"}")
				.with(r -> { r.setRemoteAddr("10.0.3." + phone.substring(8)); return r; }))).path("dev_code").asString();
		return "Bearer " + body(mvc.perform(post("/api/v1/auth/verify").contentType(JSON)
				.content("{\"phone\": \"" + phone + "\", \"code\": \"" + code + "\"}"))).path("token").asString();
	}

	private static String currentCode(String secret, int stepsAhead) {
		return Totp.code(Totp.fromBase32(secret), Totp.step(Instant.now().getEpochSecond()) + stepsAhead);
	}

	private record Member(String id, String secret) {}

	private Member createStaff(String username, String role) throws Exception {
		JsonNode created = body(mvc.perform(post("/api/v1/admin/staff").header("Authorization", BOOTSTRAP).contentType(JSON)
						.content("{\"username\": \"" + username + "\", \"display_name\": \"Test " + username + "\", \"role\": \"" + role + "\"}"))
				.andExpect(status().isCreated()));
		String setup = created.path("setup_code").asString();
		JsonNode s = body(mvc.perform(post("/api/v1/admin/auth/setup").contentType(JSON)
						.content("{\"username\": \"" + username + "\", \"setup_code\": \"" + setup + "\", \"password\": \"" + PASSWORD + "\"}"))
				.andExpect(status().isOk()));
		String secret = s.path("totp_secret").asString();
		assertThat(s.path("otpauth_uri").asString()).startsWith("otpauth://totp/JanSathi%3A" + username + "?secret=" + secret);
		mvc.perform(post("/api/v1/admin/auth/confirm").contentType(JSON)
						.content("{\"username\": \"" + username + "\", \"setup_code\": \"" + setup + "\", \"code\": \"" + currentCode(secret, -1) + "\"}"))
				.andExpect(status().isOk());
		return new Member(created.path("staff").path("id").asString(), secret);
	}

	private String login(String username, Member m) throws Exception {
		return "Bearer " + body(mvc.perform(post("/api/v1/admin/auth/login").contentType(JSON)
						.content("{\"username\": \"" + username + "\", \"password\": \"" + PASSWORD + "\", \"code\": \"" + currentCode(m.secret(), 0) + "\"}"))
				.andExpect(status().isOk())).path("token").asString();
	}

	@Test
	void totpMatchesTheRfc6238Vectors() {
		byte[] secret = "12345678901234567890".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		assertThat(Totp.code(secret, 59 / 30)).isEqualTo("287082");
		assertThat(Totp.code(secret, 1111111109L / 30)).isEqualTo("081804");
		assertThat(Totp.code(secret, 2000000000L / 30)).isEqualTo("279037");
		assertThat(Totp.fromBase32(Totp.base32(secret))).isEqualTo(secret);
	}

	@Test
	void aPersonSignsInWithPasswordAndCodeAndACodeWorksOnce() throws Exception {
		Member m = createStaff("asha", "reviewer");
		String wrongPassword = "{\"username\": \"asha\", \"password\": \"not the password\", \"code\": \"" + currentCode(m.secret(), 0) + "\"}";
		mvc.perform(post("/api/v1/admin/auth/login").contentType(JSON).content(wrongPassword)).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v1/admin/auth/login").contentType(JSON).content("{\"username\": \"asha\", \"password\": \"" + PASSWORD + "\", \"code\": \"000000\"}"))
				.andExpect(status().isUnauthorized());

		String token = login("asha", m);
		mvc.perform(get("/api/v1/admin/me").header("Authorization", token)).andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("reviewer")).andExpect(jsonPath("$.label").value("Test asha (asha)"));

		// The same code again is refused.
		mvc.perform(post("/api/v1/admin/auth/login").contentType(JSON)
						.content("{\"username\": \"asha\", \"password\": \"" + PASSWORD + "\", \"code\": \"" + currentCode(m.secret(), 0) + "\"}"))
				.andExpect(status().isUnauthorized());

		mvc.perform(post("/api/v1/admin/auth/logout").header("Authorization", token)).andExpect(status().isOk());
		mvc.perform(get("/api/v1/admin/me").header("Authorization", token)).andExpect(status().isUnauthorized());
	}

	@Test
	void fiveWrongTriesLockTheAccount() throws Exception {
		Member m = createStaff("locked", "reviewer");
		for (int i = 0; i < StaffAccounts.MAX_FAILED_LOGINS; i++) {
			mvc.perform(post("/api/v1/admin/auth/login").contentType(JSON).content("{\"username\": \"locked\", \"password\": \"wrong-password\", \"code\": \"000000\"}"))
					.andExpect(status().isUnauthorized());
		}
		mvc.perform(post("/api/v1/admin/auth/login").contentType(JSON)
						.content("{\"username\": \"locked\", \"password\": \"" + PASSWORD + "\", \"code\": \"" + currentCode(m.secret(), 1) + "\"}"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void aSetupCodeWorksOnlyUntilConfirmed() throws Exception {
		String setup = body(mvc.perform(post("/api/v1/admin/staff").header("Authorization", BOOTSTRAP).contentType(JSON)
				.content("{\"username\": \"once\", \"display_name\": \"Once\", \"role\": \"reviewer\"}"))).path("setup_code").asString();
		mvc.perform(post("/api/v1/admin/auth/setup").contentType(JSON).content("{\"username\": \"once\", \"setup_code\": \"nope\", \"password\": \"" + PASSWORD + "\"}"))
				.andExpect(status().isBadRequest());
		mvc.perform(post("/api/v1/admin/auth/setup").contentType(JSON).content("{\"username\": \"once\", \"setup_code\": \"" + setup + "\", \"password\": \"short\"}"))
				.andExpect(status().isBadRequest());
		String secret = body(mvc.perform(post("/api/v1/admin/auth/setup").contentType(JSON)
				.content("{\"username\": \"once\", \"setup_code\": \"" + setup + "\", \"password\": \"" + PASSWORD + "\"}"))).path("totp_secret").asString();
		mvc.perform(post("/api/v1/admin/auth/confirm").contentType(JSON).content("{\"username\": \"once\", \"setup_code\": \"" + setup + "\", \"code\": \"" + currentCode(secret, 0) + "\"}"))
				.andExpect(status().isOk());
		mvc.perform(post("/api/v1/admin/auth/setup").contentType(JSON).content("{\"username\": \"once\", \"setup_code\": \"" + setup + "\", \"password\": \"" + PASSWORD + "\"}"))
				.andExpect(status().isBadRequest());
		String stored = jdbc.sql("SELECT totp_secret_enc FROM staff_account WHERE username = 'once'").query(String.class).single();
		assertThat(stored).doesNotContain(secret);
	}

	@Test
	void reviewersModerateButOnlyAdminsManagePeople() throws Exception {
		Member r = createStaff("rev", "reviewer");
		String reviewer = login("rev", r);
		mvc.perform(get("/api/v1/admin/reports").header("Authorization", reviewer)).andExpect(status().isOk());
		mvc.perform(get("/api/v1/admin/grievances").header("Authorization", reviewer)).andExpect(status().isOk());
		mvc.perform(get("/api/v1/admin/volunteers").header("Authorization", reviewer)).andExpect(status().isOk());
		mvc.perform(get("/api/v1/admin/sources").header("Authorization", reviewer)).andExpect(status().isOk());

		mvc.perform(get("/api/v1/admin/staff").header("Authorization", reviewer)).andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/admin/audit").header("Authorization", reviewer)).andExpect(status().isForbidden());
		mvc.perform(post("/api/v1/admin/volunteers").header("Authorization", reviewer).contentType(JSON)
				.content("{\"phone\": \"9876530099\", \"name\": \"X\", \"organisation\": \"Y\"}")).andExpect(status().isForbidden());
		mvc.perform(post("/api/v1/admin/maintenance/re-encrypt").header("Authorization", reviewer)).andExpect(status().isForbidden());

		Member a = createStaff("boss", "admin");
		String admin = login("boss", a);
		mvc.perform(get("/api/v1/admin/staff").header("Authorization", admin)).andExpect(status().isOk());

		// Deactivating ends the session at once.
		mvc.perform(delete("/api/v1/admin/staff/" + r.id()).header("Authorization", admin)).andExpect(status().isOk());
		mvc.perform(get("/api/v1/admin/reports").header("Authorization", reviewer)).andExpect(status().isUnauthorized());
	}

	@Test
	void everyAdminCallIsAuditedWithoutItsContent() throws Exception {
		Member a = createStaff("auditor", "admin");
		String admin = login("auditor", a);
		mvc.perform(get("/api/v1/admin/grievances?status=open").header("Authorization", admin)).andExpect(status().isOk());
		String id = body(mvc.perform(post("/api/v1/grievances").contentType(JSON).content("{\"category\": \"privacy\", \"message\": \"secret words\"}"))).path("id").asString();
		mvc.perform(post("/api/v1/admin/grievances/" + id + "/resolve").header("Authorization", admin).contentType(JSON)
				.content("{\"status\": \"resolved\", \"response\": \"private reply\"}")).andExpect(status().isOk());

		String audit = mvc.perform(get("/api/v1/admin/audit").header("Authorization", admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(audit).contains("Test auditor (auditor)").contains("POST /api/v1/admin/grievances/{id}/resolve").contains(id)
				.contains("GET /api/v1/admin/grievances").doesNotContain("secret words").doesNotContain("private reply");

		// The bootstrap token is audited as what it is.
		mvc.perform(get("/api/v1/admin/reports").header("Authorization", BOOTSTRAP)).andExpect(status().isOk());
		assertThat(jdbc.sql("SELECT COUNT(*) FROM admin_audit WHERE actor_id = 'bootstrap-token' AND action = 'GET /api/v1/admin/reports'").query(Integer.class).single()).isPositive();
	}

	@Test
	void aGrievanceIsEncryptedAndThePersonReadsTheAnswerWithTheReference() throws Exception {
		JsonNode made = body(mvc.perform(post("/api/v1/grievances").contentType(JSON)
						.content("{\"category\": \"data_deletion\", \"message\": \"Delete it. My Aadhaar is 2345 6789 0123\", \"contact\": \"9876530001\", \"lang\": \"hi\"}"))
				.andExpect(status().isCreated()));
		String id = made.path("id").asString();

		var stored = jdbc.sql("SELECT message_enc, contact_enc FROM grievance WHERE id = :id").param("id", id).query().singleRow();
		assertThat(stored.toString()).doesNotContain("Delete it").doesNotContain("9876530001");

		mvc.perform(get("/api/v1/grievances/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("open")).andExpect(jsonPath("$.response").isEmpty());
		String queue = mvc.perform(get("/api/v1/admin/grievances?status=open").header("Authorization", BOOTSTRAP)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(queue).contains("Delete it").contains("[number removed]").doesNotContain("2345").contains("9876530001");

		mvc.perform(post("/api/v1/admin/grievances/" + id + "/resolve").header("Authorization", BOOTSTRAP).contentType(JSON).content("{\"status\": \"closed\", \"response\": \"x\"}"))
				.andExpect(status().isBadRequest());
		mvc.perform(post("/api/v1/admin/grievances/" + id + "/resolve").header("Authorization", BOOTSTRAP).contentType(JSON).content("{\"status\": \"resolved\", \"response\": \"Done\"}"))
				.andExpect(status().isOk());
		mvc.perform(post("/api/v1/admin/grievances/" + id + "/resolve").header("Authorization", BOOTSTRAP).contentType(JSON).content("{\"status\": \"resolved\", \"response\": \"Again\"}"))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/grievances/" + id)).andExpect(jsonPath("$.status").value("resolved")).andExpect(jsonPath("$.response").value("Done"));
		mvc.perform(get("/api/v1/grievances/not-a-reference")).andExpect(status().isNotFound());
	}

	@Test
	void aGrievanceContactIsAPhoneOrEmailAndNothingElse() throws Exception {
		mvc.perform(post("/api/v1/grievances").contentType(JSON).content("{\"category\": \"other\", \"message\": \"m\", \"contact\": \"234567890123\"}")).andExpect(status().isBadRequest());
		mvc.perform(post("/api/v1/grievances").contentType(JSON).content("{\"category\": \"nonsense\", \"message\": \"m\"}")).andExpect(status().isBadRequest());
		mvc.perform(post("/api/v1/grievances").contentType(JSON).content("{\"category\": \"other\", \"message\": \" \"}")).andExpect(status().isBadRequest());
		mvc.perform(post("/api/v1/grievances").contentType(JSON).content("{\"category\": \"other\", \"message\": \"m\", \"contact\": \"a@example.org\"}")).andExpect(status().isCreated());
	}

	private JsonNode helperOnCase(String person, String helper) throws Exception {
		String code = body(mvc.perform(post("/api/v1/me/shares").header("Authorization", person).contentType(JSON).content("{\"scopes\": [\"profile\"]}"))).path("code").asString();
		return body(mvc.perform(post("/api/v1/volunteer/join").header("Authorization", helper).contentType(JSON).content("{\"code\": \"" + code + "\"}")).andExpect(status().isOk()));
	}

	@Test
	void aPersonReportsTheirHelperAndAnAdminCanRemoveThem() throws Exception {
		String person = signInPerson("9876530002");
		String other = signInPerson("9876530003");
		mvc.perform(put("/api/v1/me/household").header("Authorization", person).contentType(JSON).content("{\"members\": [{\"id\": \"me\", \"profile\": {}}]}")).andExpect(status().isOk());
		String helperId = body(mvc.perform(post("/api/v1/admin/volunteers").header("Authorization", BOOTSTRAP).contentType(JSON)
				.content("{\"phone\": \"9876530004\", \"name\": \"Ravi\", \"organisation\": \"Seva\"}"))).path("id").asString();
		String helper = signInPerson("9876530004");
		String caseId = helperOnCase(person, helper).path("id").asString();

		mvc.perform(get("/api/v1/me/helpers").header("Authorization", person)).andExpect(jsonPath("$[0].helper").value("Ravi (Seva)")).andExpect(jsonPath("$[0].share_id").value(caseId));

		// Nobody else can report on this case, and the reason comes from a fixed list.
		mvc.perform(post("/api/v1/me/helper-reports").header("Authorization", other).contentType(JSON).content("{\"share_id\": \"" + caseId + "\", \"reason\": \"rude\"}")).andExpect(status().isNotFound());
		mvc.perform(post("/api/v1/me/helper-reports").header("Authorization", person).contentType(JSON).content("{\"share_id\": \"" + caseId + "\", \"reason\": \"nonsense\"}")).andExpect(status().isBadRequest());

		String report = body(mvc.perform(post("/api/v1/me/helper-reports").header("Authorization", person).contentType(JSON)
						.content("{\"share_id\": \"" + caseId + "\", \"reason\": \"asked_for_payment\", \"note\": \"Asked for 500 rupees, call 9876530002\"}"))
				.andExpect(status().isCreated())).path("id").asString();

		assertThat(jdbc.sql("SELECT note_enc FROM helper_report WHERE id = :id").param("id", report).query(String.class).single()).doesNotContain("rupees");
		String queue = mvc.perform(get("/api/v1/admin/helper-reports?status=open").header("Authorization", BOOTSTRAP)).andReturn().getResponse().getContentAsString();
		assertThat(queue).contains("Ravi (Seva)").contains("500 rupees").contains("[number removed]").doesNotContain("9876530002");

		Member r = createStaff("mod", "reviewer");
		String reviewer = login("mod", r);
		mvc.perform(post("/api/v1/admin/helper-reports/" + report + "/resolve").header("Authorization", reviewer).contentType(JSON)
				.content("{\"status\": \"resolved\", \"deactivate_helper\": true}")).andExpect(status().isForbidden());

		mvc.perform(post("/api/v1/admin/helper-reports/" + report + "/resolve").header("Authorization", BOOTSTRAP).contentType(JSON)
				.content("{\"status\": \"resolved\", \"resolution\": \"Partner told\", \"deactivate_helper\": true}")).andExpect(status().isOk());

		// The role and the case end together, and the person is told.
		mvc.perform(get("/api/v1/volunteer").header("Authorization", helper)).andExpect(status().isForbidden());
		assertThat(mvc.perform(get("/api/v1/me/audit").header("Authorization", person)).andReturn().getResponse().getContentAsString()).contains("helper_removed").contains("reported_helper");
		mvc.perform(get("/api/v1/me/shares").header("Authorization", person)).andExpect(jsonPath("$.length()").value(0));
		mvc.perform(get("/api/v1/admin/volunteers?include_deactivated=true").header("Authorization", BOOTSTRAP))
				.andExpect(jsonPath("$[?(@.id == '" + helperId + "')].deactivated_reason").value("reported"));
		mvc.perform(get("/api/v1/admin/volunteers").header("Authorization", BOOTSTRAP)).andExpect(jsonPath("$[?(@.id == '" + helperId + "')]").isEmpty());
	}

	@Test
	void theSourcePageShowsReviewStateAndTheRecheckButChangesNothing() throws Exception {
		JsonNode report = body(mvc.perform(get("/api/v1/admin/sources").header("Authorization", BOOTSTRAP)).andExpect(status().isOk()));
		assertThat(report.path("schemes").size()).isGreaterThan(20);
		JsonNode igndps = null;
		for (JsonNode s : report.path("schemes")) {
			if (s.path("slug").asString().equals("igndps")) {
				igndps = s;
			}
		}
		assertThat(igndps).isNotNull();
		assertThat(igndps.path("status").asString()).isNotBlank();
		assertThat(igndps.path("sources").findValuesAsString("check_status")).contains("dismissed");
		mvc.perform(post("/api/v1/admin/sources").header("Authorization", BOOTSTRAP)).andExpect(status().isMethodNotAllowed());
	}

	@Test
	void reEncryptingLeavesDataReadable() throws Exception {
		String person = signInPerson("9876530005");
		mvc.perform(put("/api/v1/me/household").header("Authorization", person).contentType(JSON).content("{\"members\": [{\"id\": \"me\", \"name\": \"Asha\", \"profile\": {}}]}")).andExpect(status().isOk());
		assertThat(body(mvc.perform(post("/api/v1/admin/maintenance/re-encrypt").header("Authorization", BOOTSTRAP)).andExpect(status().isOk())).path("rows").asInt()).isPositive();
		mvc.perform(get("/api/v1/me/household").header("Authorization", person)).andExpect(jsonPath("$.members[0].name").value("Asha"));
	}
}
