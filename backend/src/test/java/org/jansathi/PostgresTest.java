package org.jansathi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jansathi.account.Accounts;
import org.jansathi.account.Retention;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The real thing: every migration on PostgreSQL, the API connecting as the least-privileged role and
 * Flyway as the owner, and the audit logs refusing changes. Skipped unless JANSATHI_TEST_PG_URL points
 * at an empty database whose owner is jansathi/jansathi and where jansathi_app/app exists
 * (.github/workflows/ci.yml does this; docs/OPERATIONS.md §10 has the command).
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "jansathi.test.pg-url", matches = ".+")
class PostgresTest {

	private static final String URL = System.getProperty("jansathi.test.pg-url", "");

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> URL);
		registry.add("spring.datasource.username", () -> "jansathi_app");
		registry.add("spring.datasource.password", () -> "app");
		registry.add("spring.flyway.user", () -> "jansathi");
		registry.add("spring.flyway.password", () -> "jansathi");
		registry.add("jansathi.secret", () -> "test-secret-not-for-production");
	}

	@Autowired
	JdbcClient jdbc;

	@Autowired
	Accounts accounts;

	@Autowired
	Retention retention;

	private static OffsetDateTime now() {
		return OffsetDateTime.now(ZoneOffset.UTC);
	}

	private String person() {
		String id = UUID.randomUUID().toString();
		jdbc.sql("INSERT INTO app_user (id, phone_hash, phone_last4, created_at, last_seen_at) VALUES (:id, :h, '0000', :now, :now)")
				.param("id", id).param("h", UUID.randomUUID().toString().replace("-", "")).param("now", now()).update();
		return id;
	}

	private static void asOwner(String sql) throws SQLException {
		try (Connection c = DriverManager.getConnection(URL, "jansathi", "jansathi"); Statement s = c.createStatement()) {
			s.execute(sql);
		}
	}

	@Test
	void everyMigrationRanAndTheApiIsNotTheOwner() {
		assertThat(jdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo("jansathi_app");
		assertThat(jdbc.sql("SELECT tableowner FROM pg_tables WHERE tablename = 'audit_log'").query(String.class).single()).isEqualTo("jansathi");
		assertThat(jdbc.sql("SELECT COUNT(*) FROM pg_trigger WHERE NOT tgisinternal AND tgname LIKE '%insert_only' OR tgname LIKE '%no_truncate'").query(Integer.class).single()).isEqualTo(4);
	}

	@Test
	void theApiRoleCannotChangeOrEmptyTheAuditLog() {
		String user = person();
		accounts.audit(user, user, "You", "signed_in", null);
		assertThatThrownBy(() -> jdbc.sql("UPDATE audit_log SET detail = 'x' WHERE subject_id = :u").param("u", user).update())
				.rootCause().hasMessageContaining("permission denied");
		assertThatThrownBy(() -> jdbc.sql("TRUNCATE audit_log").update()).rootCause().hasMessageContaining("permission denied");
		assertThatThrownBy(() -> jdbc.sql("ALTER TABLE audit_log DISABLE TRIGGER USER").update()).rootCause().hasMessageContaining("must be owner");
		assertThatThrownBy(() -> jdbc.sql("DROP TRIGGER audit_log_insert_only ON audit_log").update()).rootCause().hasMessageContaining("must be owner");
		assertThatThrownBy(() -> jdbc.sql("UPDATE admin_audit SET status = 0").update()).rootCause().hasMessageContaining("permission denied");
		assertThatThrownBy(() -> jdbc.sql("SELECT * FROM flyway_schema_history").query().listOfRows()).rootCause().hasMessageContaining("permission denied");
	}

	@Test
	void theTriggerHoldsEvenForTheOwner() throws SQLException {
		String user = person();
		accounts.audit(user, user, "You", "signed_in", null);
		assertThatThrownBy(() -> asOwner("UPDATE audit_log SET detail = 'x' WHERE subject_id = '" + user + "'")).hasMessageContaining("insert-only");
		assertThatThrownBy(() -> asOwner("DELETE FROM audit_log WHERE subject_id = '" + user + "'")).hasMessageContaining("insert-only");
		assertThatThrownBy(() -> asOwner("TRUNCATE audit_log")).hasMessageContaining("insert-only");
		assertThatThrownBy(() -> asOwner("TRUNCATE admin_audit")).hasMessageContaining("insert-only");
		assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE subject_id = :u").param("u", user).query(Integer.class).single()).isOne();
	}

	@Test
	void aRecentRowCannotBeDeletedButErasureAndRetentionStillWork() {
		String user = person();
		accounts.audit(user, user, "You", "signed_in", null);
		assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit_log WHERE subject_id = :u").param("u", user).update()).hasMessageContaining("insert-only");

		// A year and a day old: the retention job may remove it.
		jdbc.sql("INSERT INTO audit_log (id, subject_id, actor_label, action, created_at) VALUES (:id, :s, 'You', 'old', :t)")
				.param("id", UUID.randomUUID().toString()).param("s", user).param("t", now().minusYears(1).minusDays(2)).update();
		jdbc.sql("INSERT INTO admin_audit (id, actor_id, actor_label, action, status, created_at) VALUES (:id, 'x', 'x', 'old', 200, :t)")
				.param("id", UUID.randomUUID().toString()).param("t", now().minusYears(1).minusDays(2)).update();
		var result = retention.run(now());
		assertThat(result.auditRowsDeleted()).isGreaterThanOrEqualTo(2);
		assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE subject_id = :u").param("u", user).query(Integer.class).single()).isOne();

		// The person's own erasure takes the rest, through the cascade.
		accounts.delete(user);
		assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE subject_id = :u").param("u", user).query(Integer.class).single()).isZero();
	}

	@Test
	void anInactiveAccountsRecentAuditRowsGoWithIt() {
		String user = person();
		accounts.audit(user, user, "You", "signed_in", null);
		jdbc.sql("UPDATE app_user SET last_seen_at = :t, created_at = :t WHERE id = :u").param("t", now().minusMonths(13)).param("u", user).update();
		retention.run(now());
		assertThat(jdbc.sql("SELECT COUNT(*) FROM app_user WHERE id = :u").param("u", user).query(Integer.class).single()).isZero();
	}

	@Test
	void theConflictClausesWorkHere() {
		String user = person();
		for (int i = 0; i < 2; i++) {
			jdbc.sql("INSERT INTO reminder_sent (user_id, channel, dedupe_key, sent_at) VALUES (:u, 'sms', 'k', :now) ON CONFLICT DO NOTHING")
					.param("u", user).param("now", now()).update();
		}
		assertThat(jdbc.sql("SELECT COUNT(*) FROM reminder_sent WHERE user_id = :u").param("u", user).query(Integer.class).single()).isOne();
	}
}
