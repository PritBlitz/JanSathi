package org.jansathi.admin;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jansathi.account.Crypto;
import org.jansathi.web.BadRequestException;
import org.jansathi.web.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Per-person accounts for the admin screens (roles {@code admin}, {@code reviewer}). An admin creates
 * one and passes on a one-time set-up code; the person then chooses a password and adds an
 * authenticator app. Signing in needs both, five wrong tries lock the account for 15 minutes, and
 * a code works once.
 */
@Service
public class StaffAccounts {

	static final Set<String> ROLES = Set.of("admin", "reviewer");
	static final Duration SESSION_TTL = Duration.ofHours(12);
	static final Duration SETUP_TTL = Duration.ofHours(48);
	static final Duration LOCK = Duration.ofMinutes(15);
	static final int MAX_FAILED_LOGINS = 5;
	private static final Pattern USERNAME = Pattern.compile("[a-z0-9._-]{3,60}");
	/** Compared when the username is unknown, so a wrong name takes as long as a wrong password. */
	private static final String DUMMY_HASH = Passwords.hash(UUID.randomUUID().toString());

	public record Staff(String id, String username, String displayName, String role, boolean active, boolean ready, Instant lastLoginAt, String createdBy) {}

	/** @param setupCode shown once, to pass on to the person */
	public record Created(Staff staff, String setupCode) {}

	public record Setup(String totpSecret, String otpauthUri) {}

	public record SignedIn(String token, Staff staff) {}

	private final JdbcClient jdbc;
	private final Crypto crypto;

	public StaffAccounts(JdbcClient jdbc, Crypto crypto) {
		this.jdbc = jdbc;
		this.crypto = crypto;
	}

	private static OffsetDateTime now() {
		return OffsetDateTime.now(ZoneOffset.UTC);
	}

	private static Staff row(java.sql.ResultSet rs) throws java.sql.SQLException {
		OffsetDateTime last = rs.getObject("last_login_at", OffsetDateTime.class);
		return new Staff(rs.getString("id"), rs.getString("username"), rs.getString("display_name"), rs.getString("role"), rs.getBoolean("active"),
				rs.getBoolean("totp_confirmed"), last == null ? null : last.toInstant(), rs.getString("created_by"));
	}

	public boolean anyActive() {
		return jdbc.sql("SELECT COUNT(*) FROM staff_account WHERE active = TRUE").query(Integer.class).single() > 0;
	}

	public List<Staff> list() {
		return jdbc.sql("SELECT * FROM staff_account ORDER BY username").query((rs, i) -> row(rs)).list();
	}

	private String newSetupCode(String id) {
		String code = Crypto.randomToken();
		jdbc.sql("""
				UPDATE staff_account SET setup_hash = :h, setup_expires_at = :exp, password_hash = NULL, totp_secret_enc = NULL,
				totp_confirmed = FALSE, totp_last_step = 0, failed_logins = 0, locked_until = NULL WHERE id = :id
				""").param("h", Crypto.sha256(code)).param("exp", now().plus(SETUP_TTL)).param("id", id).update();
		jdbc.sql("DELETE FROM staff_session WHERE staff_id = :id").param("id", id).update();
		return code;
	}

	@Transactional
	public Created create(String username, String displayName, String role, String createdBy) {
		String name = username == null ? "" : username.strip().toLowerCase(java.util.Locale.ROOT);
		if (!USERNAME.matcher(name).matches()) {
			throw new BadRequestException("A username has 3 to 60 letters, digits, dots, hyphens or underscores");
		}
		if (displayName == null || displayName.isBlank() || displayName.length() > 80) {
			throw new BadRequestException("A name has 1 to 80 characters");
		}
		if (role == null || !ROLES.contains(role)) {
			throw new BadRequestException("role must be admin or reviewer");
		}
		if (jdbc.sql("SELECT COUNT(*) FROM staff_account WHERE username = :u").param("u", name).query(Integer.class).single() > 0) {
			throw new BadRequestException("This username is taken");
		}
		String id = UUID.randomUUID().toString();
		jdbc.sql("""
				INSERT INTO staff_account (id, username, display_name, role, created_at, created_by)
				VALUES (:id, :u, :n, :r, :now, :by)
				""").param("id", id).param("u", name).param("n", displayName.strip()).param("r", role).param("now", now()).param("by", createdBy).update();
		String code = newSetupCode(id);
		return new Created(find(id).orElseThrow(), code);
	}

	/** A lost phone or password: a new set-up code, and the old sign-in stops working. */
	@Transactional
	public Created reset(String id) {
		Staff staff = find(id).orElseThrow(() -> new NotFoundException("No staff account with id " + id));
		return new Created(staff, newSetupCode(id));
	}

	public Optional<Staff> find(String id) {
		return jdbc.sql("SELECT * FROM staff_account WHERE id = :id").param("id", id).query((rs, i) -> row(rs)).optional();
	}

	@Transactional
	public void deactivate(String id) {
		if (jdbc.sql("UPDATE staff_account SET active = FALSE WHERE id = :id").param("id", id).update() == 0) {
			throw new NotFoundException("No staff account with id " + id);
		}
		jdbc.sql("DELETE FROM staff_session WHERE staff_id = :id").param("id", id).update();
	}

	private record Row(String id, String secretEnc, boolean confirmed, long lastStep, String passwordHash, int failed, OffsetDateTime lockedUntil, String setupHash, OffsetDateTime setupExpires) {}

	private Optional<Row> byUsername(String username) {
		return jdbc.sql("SELECT * FROM staff_account WHERE username = :u AND active = TRUE")
				.param("u", username == null ? "" : username.strip().toLowerCase(java.util.Locale.ROOT))
				.query((rs, i) -> new Row(rs.getString("id"), rs.getString("totp_secret_enc"), rs.getBoolean("totp_confirmed"), rs.getLong("totp_last_step"),
						rs.getString("password_hash"), rs.getInt("failed_logins"), rs.getObject("locked_until", OffsetDateTime.class),
						rs.getString("setup_hash"), rs.getObject("setup_expires_at", OffsetDateTime.class)))
				.optional();
	}

	private Row forSetup(String username, String setupCode) {
		return byUsername(username)
				.filter(r -> r.setupHash() != null && r.setupExpires().isAfter(now()))
				.filter(r -> setupCode != null && Crypto.sameHash(r.setupHash(), Crypto.sha256(setupCode)))
				.orElseThrow(() -> new BadRequestException("This set-up code is not valid. Ask an admin for a new one."));
	}

	/** First step of set-up: the password, and the secret for the authenticator app. */
	public Setup choosePassword(String username, String setupCode, String password) {
		Row r = forSetup(username, setupCode);
		if (password == null || password.length() < Passwords.MIN_LENGTH || password.length() > 200) {
			throw new BadRequestException("A password has at least " + Passwords.MIN_LENGTH + " characters");
		}
		String secret = Totp.base32(Crypto.randomBytes(20));
		jdbc.sql("UPDATE staff_account SET password_hash = :p, totp_secret_enc = :s WHERE id = :id")
				.param("p", Passwords.hash(password)).param("s", crypto.encrypt(secret)).param("id", r.id()).update();
		return new Setup(secret, Totp.uri(username.strip().toLowerCase(java.util.Locale.ROOT), secret));
	}

	/** Second step: a first code from the app proves it was added. The set-up code then stops working. */
	public void confirm(String username, String setupCode, String code) {
		Row r = forSetup(username, setupCode);
		if (r.secretEnc() == null) {
			throw new BadRequestException("Choose a password first");
		}
		long step = Totp.matchingStep(Totp.fromBase32(crypto.decrypt(r.secretEnc())), code == null ? "" : code.strip(), Totp.step(Instant.now().getEpochSecond()));
		if (step < 0) {
			throw new BadRequestException("That code is not right. Check the app and try again.");
		}
		jdbc.sql("UPDATE staff_account SET totp_confirmed = TRUE, totp_last_step = :s, setup_hash = NULL, setup_expires_at = NULL WHERE id = :id")
				.param("s", step).param("id", r.id()).update();
	}

	/** One answer for every failure, so sign-in doesn't say which part was wrong or that a name exists. */
	private static ResponseStatusException refused() {
		return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "These details are not right, or the account is locked for a while");
	}

	public SignedIn login(String username, String password, String code) {
		Optional<Row> found = byUsername(username).filter(r -> r.passwordHash() != null && r.confirmed());
		boolean passwordOk = Passwords.matches(password == null ? "" : password, found.map(Row::passwordHash).orElse(DUMMY_HASH));
		if (found.isEmpty()) {
			throw refused();
		}
		Row r = found.get();
		if (r.lockedUntil() != null && r.lockedUntil().isAfter(now())) {
			throw refused();
		}
		long step = Totp.matchingStep(Totp.fromBase32(crypto.decrypt(r.secretEnc())), code == null ? "" : code.strip(), Totp.step(Instant.now().getEpochSecond()));
		if (!passwordOk || step <= r.lastStep()) {
			int failed = r.failed() + 1;
			jdbc.sql("UPDATE staff_account SET failed_logins = :f, locked_until = :l WHERE id = :id")
					.param("f", failed >= MAX_FAILED_LOGINS ? 0 : failed)
					.param("l", failed >= MAX_FAILED_LOGINS ? now().plus(LOCK) : null).param("id", r.id()).update();
			throw refused();
		}
		jdbc.sql("UPDATE staff_account SET failed_logins = 0, locked_until = NULL, totp_last_step = :s, last_login_at = :now WHERE id = :id")
				.param("s", step).param("now", now()).param("id", r.id()).update();
		String token = Crypto.randomToken();
		jdbc.sql("INSERT INTO staff_session (token_hash, staff_id, created_at, expires_at) VALUES (:t, :s, :now, :exp)")
				.param("t", Crypto.sha256(token)).param("s", r.id()).param("now", now()).param("exp", now().plus(SESSION_TTL)).update();
		return new SignedIn(token, find(r.id()).orElseThrow());
	}

	public void logout(String token) {
		jdbc.sql("DELETE FROM staff_session WHERE token_hash = :t").param("t", Crypto.sha256(token)).update();
	}

	public Optional<AdminPrincipal> bySession(String token) {
		if (token == null || token.isBlank()) {
			return Optional.empty();
		}
		return jdbc.sql("""
				SELECT a.id, a.display_name, a.username, a.role FROM staff_session s JOIN staff_account a ON a.id = s.staff_id
				WHERE s.token_hash = :t AND s.expires_at > :now AND a.active = TRUE
				""").param("t", Crypto.sha256(token)).param("now", now())
				.query((rs, i) -> new AdminPrincipal(rs.getString("id"), rs.getString("display_name") + " (" + rs.getString("username") + ")", rs.getString("role")))
				.optional();
	}
}
