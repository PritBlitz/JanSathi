package org.jansathi.account;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jansathi.JansathiProperties;
import org.jansathi.web.BadRequestException;
import org.jansathi.web.OnDeviceOnlyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phone + one-time-code sign-in, sessions and the audit log. The phone number itself is never
 * stored: only a keyed hash (to find the account) and the last 4 digits (so a person recognises it).
 */
@Service
public class Accounts {

	private static final Logger log = LoggerFactory.getLogger(Accounts.class);
	/** Indian mobile numbers: 10 digits starting 6–9, optionally with +91 / 91 / 0 in front. */
	private static final Pattern MOBILE = Pattern.compile("^(?:\\+?91|0)?([6-9]\\d{9})$");
	static final Duration CODE_TTL = Duration.ofMinutes(5);
	static final int MAX_ATTEMPTS = 5;
	static final int MAX_CODES_PER_WINDOW = 3;
	static final Duration CODE_WINDOW = Duration.ofMinutes(15);
	static final Duration SESSION_TTL = Duration.ofDays(30);

	/** A signed-in person, as the API sees them. */
	public record User(String id, String phoneLast4, String role, String displayName, String organisation, String lang) {

		public boolean isVolunteer() {
			return "volunteer".equals(role);
		}

		/** How this person appears in someone else's audit log. */
		public String label() {
			return isVolunteer() ? (displayName == null ? "Helper" : displayName) + (organisation == null ? "" : " (" + organisation + ")") : "You";
		}
	}

	private final JdbcClient jdbc;
	private final Crypto crypto;
	private final SmsSender sms;

	public Accounts(JdbcClient jdbc, Crypto crypto, SmsSender sms, JansathiProperties properties) {
		this.jdbc = jdbc;
		this.crypto = crypto;
		this.sms = sms;
		if ("dev".equals(properties.sms().provider())) {
			log.warn("SMS provider is 'dev': sign-in codes are shown on screen. Configure a real SMS provider before launch.");
		}
	}

	private static OffsetDateTime now() {
		return OffsetDateTime.now(ZoneOffset.UTC);
	}

	/** The 10-digit number, or a 400 error. */
	public static String normalisePhone(String raw) {
		String digits = raw == null ? "" : raw.replaceAll("[\\s-]", "");
		var m = MOBILE.matcher(digits);
		if (!m.matches()) {
			throw new BadRequestException("Enter a 10-digit Indian mobile number");
		}
		return m.group(1);
	}

	public record CodeSent(String devCode) {}

	/** Sends a sign-in code. At most 3 per number per 15 minutes. */
	@Transactional
	public CodeSent sendCode(String rawPhone, String lang) {
		String phone = normalisePhone(rawPhone);
		String phoneHash = crypto.keyedHash("phone:" + phone);
		Integer recent = jdbc.sql("SELECT COUNT(*) FROM otp_challenge WHERE phone_hash = :p AND created_at > :since")
				.param("p", phoneHash).param("since", now().minus(CODE_WINDOW)).query(Integer.class).single();
		if (recent >= MAX_CODES_PER_WINDOW) {
			throw new BadRequestException("Too many codes. Wait 15 minutes and try again.");
		}
		String code = Crypto.randomDigits(6);
		jdbc.sql("""
				INSERT INTO otp_challenge (id, phone_hash, code_hash, created_at, expires_at)
				VALUES (:id, :p, :c, :now, :exp)
				""")
				.param("id", UUID.randomUUID().toString()).param("p", phoneHash)
				.param("c", crypto.keyedHash("otp:" + phoneHash + ":" + code))
				.param("now", now()).param("exp", now().plus(CODE_TTL)).update();
		return new CodeSent(sms.sendCode(phone, code, lang));
	}

	public record SignedIn(String token, User user) {}

	/**
	 * Checks the newest unused code for this number; creates the account on first sign-in. A wrong
	 * code must not roll back: the failed-attempt count is what stops guessing.
	 */
	@Transactional(noRollbackFor = BadRequestException.class)
	public SignedIn verify(String rawPhone, String code, String lang) {
		String phone = normalisePhone(rawPhone);
		String phoneHash = crypto.keyedHash("phone:" + phone);
		var challenge = jdbc.sql("""
				SELECT id, code_hash, attempts FROM otp_challenge
				WHERE phone_hash = :p AND used = FALSE AND expires_at > :now
				ORDER BY created_at DESC LIMIT 1
				""").param("p", phoneHash).param("now", now())
				.query((rs, i) -> new Object[] {rs.getString("id"), rs.getString("code_hash"), rs.getInt("attempts")})
				.optional()
				.orElseThrow(() -> new BadRequestException("The code has expired. Ask for a new one."));
		String id = (String) challenge[0];
		if ((int) challenge[2] >= MAX_ATTEMPTS) {
			throw new BadRequestException("Too many wrong codes. Ask for a new one.");
		}
		String given = code == null ? "" : code.strip();
		if (!Crypto.sameHash((String) challenge[1], crypto.keyedHash("otp:" + phoneHash + ":" + given))) {
			jdbc.sql("UPDATE otp_challenge SET attempts = attempts + 1 WHERE id = :id").param("id", id).update();
			throw new BadRequestException("That code is not right. Check the SMS and try again.");
		}
		jdbc.sql("UPDATE otp_challenge SET used = TRUE WHERE id = :id").param("id", id).update();

		String userId = accountFor(phone, phoneHash)
				.orElseGet(() -> {
					String newId = UUID.randomUUID().toString();
					jdbc.sql("INSERT INTO app_user (id, phone_hash, phone_last4, created_at, lang) VALUES (:id, :p, :l4, :now, :lang)")
							.param("id", newId).param("p", phoneHash).param("l4", phone.substring(6)).param("now", now()).param("lang", lang).update();
					return newId;
				});
		jdbc.sql("UPDATE app_user SET last_login_at = :now WHERE id = :id").param("now", now()).param("id", userId).update();
		String token = Crypto.randomToken();
		jdbc.sql("INSERT INTO auth_session (token_hash, user_id, created_at, expires_at) VALUES (:t, :u, :now, :exp)")
				.param("t", Crypto.sha256(token)).param("u", userId).param("now", now()).param("exp", now().plus(SESSION_TTL)).update();
		audit(userId, userId, "You", "signed_in", null);
		return new SignedIn(token, find(userId).orElseThrow());
	}

	public Optional<User> find(String id) {
		return jdbc.sql("SELECT * FROM app_user WHERE id = :id").param("id", id)
				.query((rs, i) -> new User(rs.getString("id"), rs.getString("phone_last4"), rs.getString("role"),
						rs.getString("display_name"), rs.getString("organisation"), rs.getString("lang")))
				.optional();
	}

	/** The user for a session token, if it is valid. */
	public Optional<User> bySession(String token) {
		if (token == null || token.isBlank()) {
			return Optional.empty();
		}
		Optional<String> id = jdbc.sql("SELECT user_id FROM auth_session WHERE token_hash = :t AND expires_at > :now")
				.param("t", Crypto.sha256(token)).param("now", now()).query(String.class).optional();
		id.ifPresent(this::seen);
		return id.flatMap(this::find);
	}

	/** Remembers the day of the last visit, for the inactivity rules: at most one write a day. */
	private void seen(String id) {
		jdbc.sql("UPDATE app_user SET last_seen_at = :now WHERE id = :id AND (last_seen_at IS NULL OR last_seen_at < :since)")
				.param("now", now()).param("id", id).param("since", now().minusDays(1)).update();
	}

	/**
	 * The account for this number. After a key rotation the row may still hold the old hash: it is
	 * found with the old key and moved to the new one, the only moment the number is known.
	 */
	private Optional<String> accountFor(String phone, String phoneHash) {
		Optional<String> id = jdbc.sql("SELECT id FROM app_user WHERE phone_hash = :p").param("p", phoneHash).query(String.class).optional();
		String old = crypto.previousKeyedHash("phone:" + phone);
		if (id.isPresent() || old == null) {
			return id;
		}
		id = jdbc.sql("SELECT id FROM app_user WHERE phone_hash = :p").param("p", old).query(String.class).optional();
		id.ifPresent(found -> jdbc.sql("UPDATE app_user SET phone_hash = :p WHERE id = :id").param("p", phoneHash).param("id", found).update());
		return id;
	}

	/** True when this is the number the person signed in with. Reminders go to no other number. */
	public boolean ownsPhone(String userId, String phone) {
		String stored = jdbc.sql("SELECT phone_hash FROM app_user WHERE id = :id").param("id", userId).query(String.class).single();
		return Crypto.sameHash(stored, crypto.keyedHash("phone:" + phone)) || Crypto.sameHash(stored, crypto.previousKeyedHash("phone:" + phone));
	}

	/** True when this person is under 18 by their own answer: nothing of theirs stays here (PRIVACY §6). */
	public boolean onDeviceOnly(String userId) {
		return jdbc.sql("SELECT on_device_only FROM app_user WHERE id = :id").param("id", userId).query(Boolean.class).optional().orElse(false);
	}

	/** Throws when the person is in on-device-only mode. */
	public void requireNotOnDeviceOnly(String userId) {
		if (onDeviceOnly(userId)) {
			throw new OnDeviceOnlyException();
		}
	}

	public void signOut(String token) {
		jdbc.sql("DELETE FROM auth_session WHERE token_hash = :t").param("t", Crypto.sha256(token)).update();
	}

	/** Deletes the account and everything linked to it (cascades). */
	@Transactional
	public void delete(String userId) {
		jdbc.sql("DELETE FROM app_user WHERE id = :id").param("id", userId).update();
	}

	/** Makes the person with this number a helper (creates the account if needed). */
	@Transactional
	public User makeVolunteer(String rawPhone, String name, String organisation) {
		String phone = normalisePhone(rawPhone);
		String phoneHash = crypto.keyedHash("phone:" + phone);
		String id = accountFor(phone, phoneHash).orElse(null);
		if (id == null) {
			id = UUID.randomUUID().toString();
			jdbc.sql("INSERT INTO app_user (id, phone_hash, phone_last4, created_at) VALUES (:id, :p, :l4, :now)")
					.param("id", id).param("p", phoneHash).param("l4", phone.substring(6)).param("now", now()).update();
		}
		// Registering starts the 90-day idle clock, also for someone who had an account for years.
		jdbc.sql("""
				UPDATE app_user SET role = 'volunteer', display_name = :n, organisation = :o, last_seen_at = :now,
				deactivated_at = NULL, deactivated_reason = NULL WHERE id = :id
				""")
				.param("n", name).param("o", organisation).param("now", now()).param("id", id).update();
		return find(id).orElseThrow();
	}

	/**
	 * Ends someone's helper role. The cases they hold end with it, and each person sees that in their
	 * audit log. The account stays: it is also a person's own account.
	 *
	 * @param reason {@code admin}, {@code idle} or {@code reported}
	 * @return false when this id is not an active helper
	 */
	@Transactional
	public boolean deactivateHelper(String id, String reason) {
		int n = jdbc.sql("UPDATE app_user SET role = 'user', deactivated_at = :now, deactivated_reason = :r WHERE id = :id AND role = 'volunteer'")
				.param("now", now()).param("r", reason).param("id", id).update();
		if (n == 0) {
			return false;
		}
		List<String> owners = jdbc.sql("SELECT user_id FROM case_share WHERE volunteer_id = :v AND revoked_at IS NULL AND expires_at > :now")
				.param("v", id).param("now", now()).query(String.class).list();
		jdbc.sql("UPDATE case_share SET revoked_at = :now WHERE volunteer_id = :v AND revoked_at IS NULL").param("now", now()).param("v", id).update();
		owners.forEach(owner -> audit(owner, id, "JanSathi", "helper_removed", null));
		return true;
	}

	/** Helpers with no activity since the cutoff lose the role (PRIVACY §4.1). */
	int deactivateIdleHelpers(OffsetDateTime cutoff) {
		List<String> idle = jdbc.sql("SELECT id FROM app_user WHERE role = 'volunteer' AND COALESCE(last_seen_at, last_login_at, created_at) < :t")
				.param("t", cutoff).query(String.class).list();
		idle.forEach(id -> deactivateHelper(id, "idle"));
		return idle.size();
	}

	/** A helper as the admin screens see them. Role {@code user} with a date means deactivated. */
	public record Helper(
			String id, String phoneLast4, String role, String displayName, String organisation, String lang,
			Instant lastSeenAt, Instant deactivatedAt, String deactivatedReason) {}

	private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
		OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
		return t == null ? null : t.toInstant();
	}

	public List<Helper> volunteers(boolean includeDeactivated) {
		String where = includeDeactivated ? "role = 'volunteer' OR deactivated_at IS NOT NULL" : "role = 'volunteer'";
		return jdbc.sql("SELECT * FROM app_user WHERE " + where + " ORDER BY display_name")
				.query((rs, i) -> new Helper(rs.getString("id"), rs.getString("phone_last4"), rs.getString("role"),
						rs.getString("display_name"), rs.getString("organisation"), rs.getString("lang"),
						instant(rs, "last_seen_at"), instant(rs, "deactivated_at"), rs.getString("deactivated_reason")))
				.list();
	}

	// ---------- audit log ----------

	public record AuditEntry(Instant at, String actor, String action, String detail) {}

	public void audit(String subjectId, String actorId, String actorLabel, String action, String detail) {
		jdbc.sql("""
				INSERT INTO audit_log (id, subject_id, actor_id, actor_label, action, detail, created_at)
				VALUES (:id, :s, :a, :l, :act, :d, :now)
				""")
				.param("id", UUID.randomUUID().toString()).param("s", subjectId).param("a", actorId).param("l", actorLabel)
				.param("act", action).param("d", detail).param("now", now()).update();
	}

	public List<AuditEntry> auditFor(String subjectId) {
		return jdbc.sql("SELECT * FROM audit_log WHERE subject_id = :s ORDER BY created_at DESC LIMIT 200").param("s", subjectId)
				.query((rs, i) -> new AuditEntry(rs.getObject("created_at", OffsetDateTime.class).toInstant(),
						rs.getString("actor_label"), rs.getString("action"), rs.getString("detail")))
				.list();
	}

	/** Removes used and expired codes and expired sessions (they are useless once past). */
	@Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
	public void cleanUp() {
		jdbc.sql("DELETE FROM otp_challenge WHERE expires_at < :t").param("t", now().minus(CODE_WINDOW)).update();
		jdbc.sql("DELETE FROM auth_session WHERE expires_at < :t").param("t", now()).update();
		jdbc.sql("DELETE FROM telegram_link WHERE expires_at < :t").param("t", now()).update();
		jdbc.sql("DELETE FROM staff_session WHERE expires_at < :t").param("t", now()).update();
		jdbc.sql("DELETE FROM share_code_failure WHERE failed_at < :t").param("t", now().minusHours(1)).update();
	}
}
