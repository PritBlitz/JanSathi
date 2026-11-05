package org.jansathi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jansathi.account.Crypto;
import org.jansathi.account.Retention;
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

/** Retention, the under-18 rule, outcome counts, headers, metrics and the secret rotation. */
@SpringBootTest
@AutoConfigureMockMvc
class SafeguardsTest {

	private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
	private static final String ADMIN = "Bearer test-admin-token";
	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> "jdbc:h2:mem:safeguards;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
		registry.add("jansathi.admin-token", () -> "test-admin-token");
		registry.add("jansathi.secret", () -> "test-secret-not-for-production");
		registry.add("jansathi.web.metrics-token", () -> "metrics-token");
		registry.add("management.defaults.metrics.export.enabled", () -> "true");
		registry.add("management.prometheus.metrics.export.enabled", () -> "true");
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	Retention retention;

	private JsonNode body(org.springframework.test.web.servlet.ResultActions r) throws Exception {
		return MAPPER.readTree(r.andReturn().getResponse().getContentAsString());
	}

	private String signIn(String phone) throws Exception {
		String code = body(mvc.perform(post("/api/v1/auth/code").contentType(JSON).content("{\"phone\": \"" + phone + "\"}")
				.with(r -> { r.setRemoteAddr("10.0.4." + phone.substring(8)); return r; }))).path("dev_code").asString();
		return "Bearer " + body(mvc.perform(post("/api/v1/auth/verify").contentType(JSON)
				.content("{\"phone\": \"" + phone + "\", \"code\": \"" + code + "\"}"))).path("token").asString();
	}

	private String userId(String auth) throws Exception {
		return body(mvc.perform(get("/api/v1/me").header("Authorization", auth))).path("id").asString();
	}

	private static OffsetDateTime now() {
		return OffsetDateTime.now(ZoneOffset.UTC);
	}

	private void household(String auth, String age, int expected) throws Exception {
		mvc.perform(put("/api/v1/me/household").header("Authorization", auth).contentType(JSON)
						.content("{\"members\": [{\"id\": \"me\", \"name\": \"Me\", \"profile\": {\"age\": " + age + "}}]}"))
				.andExpect(status().is(expected));
	}

	// ---------- under 18 ----------

	@Test
	void underEighteenStaysOnTheDevice() throws Exception {
		String teen = signIn("9876540001");
		household(teen, "\"18_39\"", 200);
		String code = body(mvc.perform(post("/api/v1/me/shares").header("Authorization", teen).contentType(JSON).content("{\"scopes\": [\"profile\"]}"))).path("code").asString();
		assertThat(code).isNotBlank();
		jdbc.sql("INSERT INTO guide_message (id, user_id, role, content_enc, created_at) VALUES (:id, :u, 'user', 'x', :now)")
				.param("id", UUID.randomUUID().toString()).param("u", userId(teen)).param("now", now()).update();

		mvc.perform(put("/api/v1/me/household").header("Authorization", teen).contentType(JSON)
						.content("{\"members\": [{\"id\": \"me\", \"profile\": {\"age\": \"under_18\"}}]}"))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("on_device_only"));

		String id = userId(teen);
		// What the server held has gone, and sharing has ended.
		assertThat(jdbc.sql("SELECT COUNT(*) FROM user_data WHERE user_id = :u").param("u", id).query(Integer.class).single()).isZero();
		assertThat(jdbc.sql("SELECT COUNT(*) FROM guide_message WHERE user_id = :u").param("u", id).query(Integer.class).single()).isZero();
		mvc.perform(get("/api/v1/me/shares").header("Authorization", teen)).andExpect(jsonPath("$.length()").value(0));
		mvc.perform(get("/api/v1/me/restrictions").header("Authorization", teen)).andExpect(jsonPath("$.on_device_only").value(true));

		// Everything that keeps or passes on data is closed.
		mvc.perform(post("/api/v1/me/shares").header("Authorization", teen).contentType(JSON).content("{}"))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("on_device_only"));
		mvc.perform(post("/api/v1/guide/messages").header("Authorization", teen).contentType(JSON).content("{\"text\": \"hello\"}")).andExpect(status().isForbidden());

		// What needs no storage stays open, and correcting the answer lifts it.
		mvc.perform(post("/api/v1/match").contentType(JSON).content("{\"profile\": {\"age\": \"under_18\"}}")).andExpect(status().isOk());
		household(teen, "\"18_39\"", 200);
		mvc.perform(get("/api/v1/me/restrictions").header("Authorization", teen)).andExpect(jsonPath("$.on_device_only").value(false));
	}

	@Test
	void anExactAgeCountsAndAChildAddedByAParentDoesNot() throws Exception {
		String me = signIn("9876540002");
		household(me, "17", 403);
		household(me, "18", 200);
		mvc.perform(put("/api/v1/me/household").header("Authorization", me).contentType(JSON)
						.content("{\"members\": [{\"id\": \"me\", \"profile\": {\"age\": \"40_59\"}}, {\"id\": \"child\", \"profile\": {\"age\": \"under_18\"}}]}"))
				.andExpect(status().isOk());
	}

	// ---------- retention ----------

	private String oldAccount(String phone, OffsetDateTime lastSeen) throws Exception {
		String auth = signIn(phone);
		String id = userId(auth);
		jdbc.sql("UPDATE app_user SET last_seen_at = :t, last_login_at = :t, created_at = :t WHERE id = :id").param("t", lastSeen).param("id", id).update();
		return id;
	}

	private int count(String table, String where, String id) {
		return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE " + where + " = :id").param("id", id).query(Integer.class).single();
	}

	@Test
	void anAccountInactiveForTwelveMonthsGoesWithEverythingLinked() throws Exception {
		String gone = oldAccount("9876540003", now().minusMonths(13));
		String kept = oldAccount("9876540004", now().minusMonths(11));
		assertThat(count("audit_log", "subject_id", gone)).isPositive();

		retention.run(now());

		assertThat(count("app_user", "id", gone)).isZero();
		assertThat(count("audit_log", "subject_id", gone)).isZero();
		assertThat(count("app_user", "id", kept)).isOne();
	}

	@Test
	void theLastVisitCountsNotTheLastSignIn() throws Exception {
		String auth = signIn("9876540005");
		String id = userId(auth);
		jdbc.sql("UPDATE app_user SET last_seen_at = :t, last_login_at = :t, created_at = :t WHERE id = :id").param("t", now().minusMonths(13)).param("id", id).update();
		mvc.perform(get("/api/v1/me").header("Authorization", auth)).andExpect(status().isOk());
		retention.run(now());
		assertThat(count("app_user", "id", id)).isOne();
	}

	@Test
	void auditRowsAndClosedCasesGoAfterAYear() throws Exception {
		String auth = signIn("9876540006");
		String id = userId(auth);
		String oldRow = UUID.randomUUID().toString();
		jdbc.sql("INSERT INTO audit_log (id, subject_id, actor_label, action, created_at) VALUES (:id, :s, 'You', 'old', :t)")
				.param("id", oldRow).param("s", id).param("t", now().minusYears(1).minusDays(3)).update();
		String oldAdmin = UUID.randomUUID().toString();
		jdbc.sql("INSERT INTO admin_audit (id, actor_id, actor_label, action, status, created_at) VALUES (:id, 'x', 'x', 'old', 200, :t)")
				.param("id", oldAdmin).param("t", now().minusYears(1).minusDays(3)).update();
		String oldGrievance = UUID.randomUUID().toString();
		jdbc.sql("INSERT INTO grievance (id, received_at, category, message_enc, status, resolved_at) VALUES (:id, :t, 'other', 'x', 'resolved', :t)")
				.param("id", oldGrievance).param("t", now().minusYears(1).minusDays(3)).update();
		String openGrievance = UUID.randomUUID().toString();
		jdbc.sql("INSERT INTO grievance (id, received_at, category, message_enc) VALUES (:id, :t, 'other', 'x')")
				.param("id", openGrievance).param("t", now().minusYears(2)).update();

		retention.run(now());

		assertThat(count("audit_log", "id", oldRow)).isZero();
		assertThat(count("audit_log", "subject_id", id)).isPositive();
		assertThat(count("admin_audit", "id", oldAdmin)).isZero();
		assertThat(count("grievance", "id", oldGrievance)).isZero();
		assertThat(count("grievance", "id", openGrievance)).isOne();
	}

	@Test
	void aHelperIdleForNinetyDaysLosesTheRoleAndTheirCasesEnd() throws Exception {
		String person = signIn("9876540007");
		mvc.perform(put("/api/v1/me/household").header("Authorization", person).contentType(JSON).content("{\"members\": [{\"id\": \"me\", \"profile\": {}}]}")).andExpect(status().isOk());
		String helperId = body(mvc.perform(post("/api/v1/admin/volunteers").header("Authorization", ADMIN).contentType(JSON)
				.content("{\"phone\": \"9876540008\", \"name\": \"Idle\", \"organisation\": \"CSC\"}"))).path("id").asString();
		String helper = signIn("9876540008");
		String code = body(mvc.perform(post("/api/v1/me/shares").header("Authorization", person).contentType(JSON).content("{\"scopes\": [\"profile\"]}"))).path("code").asString();
		String caseId = body(mvc.perform(post("/api/v1/volunteer/join").header("Authorization", helper).contentType(JSON).content("{\"code\": \"" + code + "\"}"))).path("id").asString();

		retention.run(now());
		mvc.perform(get("/api/v1/volunteer").header("Authorization", helper)).andExpect(status().isOk());

		jdbc.sql("UPDATE app_user SET last_seen_at = :t, last_login_at = :t WHERE id = :id").param("t", now().minusDays(91)).param("id", helperId).update();
		assertThat(retention.run(now()).helpersDeactivated()).isOne();

		mvc.perform(get("/api/v1/volunteer/cases/" + caseId).header("Authorization", helper)).andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/me/shares").header("Authorization", person)).andExpect(jsonPath("$.length()").value(0));
		assertThat(mvc.perform(get("/api/v1/me/audit").header("Authorization", person)).andReturn().getResponse().getContentAsString()).contains("helper_removed");

		// Registering again brings them back.
		mvc.perform(post("/api/v1/admin/volunteers").header("Authorization", ADMIN).contentType(JSON)
				.content("{\"phone\": \"9876540008\", \"name\": \"Idle\", \"organisation\": \"CSC\"}")).andExpect(status().isOk());
		mvc.perform(get("/api/v1/volunteer").header("Authorization", helper)).andExpect(status().isOk());
	}

	@Test
	void wrongShareCodesAreCountedInTheDatabase() throws Exception {
		mvc.perform(post("/api/v1/admin/volunteers").header("Authorization", ADMIN).contentType(JSON)
				.content("{\"phone\": \"9876540009\", \"name\": \"H\", \"organisation\": \"O\"}")).andExpect(status().isOk());
		String helper = signIn("9876540009");
		mvc.perform(post("/api/v1/volunteer/join").header("Authorization", helper).contentType(JSON).content("{\"code\": \"ZZZZZZ\"}")).andExpect(status().isNotFound());
		assertThat(jdbc.sql("SELECT COUNT(*) FROM share_code_failure").query(Integer.class).single()).isPositive();
	}

	// ---------- outcomes ----------

	private void outcome(String scheme, String status, String reason, int times) throws Exception {
		for (int i = 0; i < times; i++) {
			String r = reason == null ? "" : ", \"reason_code\": \"" + reason + "\"";
			mvc.perform(post("/api/v1/outcomes").contentType(JSON).content("{\"scheme\": \"" + scheme + "\", \"status\": \"" + status + "\"" + r + "}")).andExpect(status().isNoContent());
		}
	}

	@Test
	void outcomesAreCountersAndShowOnlyGroupsOfTen() throws Exception {
		mvc.perform(get("/api/v1/outcomes/reason-codes")).andExpect(jsonPath("$.reason_codes.length()").value(7)).andExpect(jsonPath("$.reason_codes[0]").value("documents_missing"));

		outcome("pm-kisan", "rejected", "income_limit", 9);
		outcome("pm-kisan", "approved", null, 20);
		mvc.perform(get("/api/v1/schemes/pm-kisan/outcomes")).andExpect(status().isOk())
				.andExpect(jsonPath("$.total").isEmpty()).andExpect(jsonPath("$.reasons.length()").value(0));

		outcome("pm-kisan", "rejected", "income_limit", 1);
		outcome("pm-kisan", "rejected", "age_limit", 3);
		// Ten reasons in all are visible, but "age_limit" alone stays below the threshold.
		mvc.perform(get("/api/v1/schemes/pm-kisan/outcomes"))
				.andExpect(jsonPath("$.total").value(13)).andExpect(jsonPath("$.reasons.length()").value(1))
				.andExpect(jsonPath("$.reasons[0].code").value("income_limit")).andExpect(jsonPath("$.reasons[0].count").value(10));

		assertThat(jdbc.sql("SELECT COUNT(*) FROM outcome_count WHERE scheme = 'pm-kisan'").query(Integer.class).single()).isEqualTo(3);
	}

	@Test
	void outcomesTakeOnlyTheFixedList() throws Exception {
		mvc.perform(post("/api/v1/outcomes").contentType(JSON).content("{\"scheme\": \"nope\", \"status\": \"approved\"}")).andExpect(status().isBadRequest());
		mvc.perform(post("/api/v1/outcomes").contentType(JSON).content("{\"scheme\": \"pm-kisan\", \"status\": \"maybe\"}")).andExpect(status().isBadRequest());
		mvc.perform(post("/api/v1/outcomes").contentType(JSON).content("{\"scheme\": \"pm-kisan\", \"status\": \"rejected\", \"reason_code\": \"free text\"}")).andExpect(status().isBadRequest());
		mvc.perform(post("/api/v1/outcomes").contentType(JSON).content("{\"scheme\": \"pm-kisan\", \"status\": \"rejected\"}")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/schemes/nope/outcomes")).andExpect(status().isNotFound());
	}

	// ---------- headers and metrics ----------

	@Test
	void everyResponseCarriesThePolicy() throws Exception {
		String csp = mvc.perform(get("/api/v1/categories")).andExpect(status().isOk())
				.andExpect(header().string("Strict-Transport-Security", "max-age=31536000"))
				.andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
				.andReturn().getResponse().getHeader("Content-Security-Policy");
		assertThat(csp).contains("default-src 'self'").contains("frame-ancestors 'none'").contains("https://tile.openstreetmap.org")
				.contains("https://overpass-api.de").contains("https://nominatim.openstreetmap.org").contains("https://api.postalpincode.in")
				.contains("'wasm-unsafe-eval'").doesNotContain("'unsafe-eval'");
	}

	@Test
	void metricsNeedTheirTokenAndHealthDoesNot() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());
		mvc.perform(get("/actuator/prometheus")).andExpect(status().isNotFound());
		mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer wrong")).andExpect(status().isNotFound());
		mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer metrics-token")).andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("jvm_memory_used_bytes")));
	}

	// ---------- rotating the secret ----------

	private static JansathiProperties withSecret(String secret) {
		return new JansathiProperties(null, false, false, null, null, null, secret, null, null, null);
	}

	@Test
	void theOldKeyStillReadsDuringARotationAndPhoneHashesMoveAtSignIn() {
		Crypto old = new Crypto(withSecret("old-secret-old-secret-old-secret-1"));
		String stored = old.encrypt("kept");
		Crypto rotating = new Crypto(withSecret("new-secret-new-secret-new-secret-1"), "old-secret-old-secret-old-secret-1");

		assertThat(rotating.decrypt(stored)).isEqualTo("kept");
		assertThat(rotating.previousKeyedHash("phone:9876543210")).isEqualTo(old.keyedHash("phone:9876543210"));
		assertThat(rotating.keyedHash("phone:9876543210")).isNotEqualTo(old.keyedHash("phone:9876543210"));
		assertThat(new Crypto(withSecret("x".repeat(40))).previousKeyedHash("a")).isNull();
	}
}
