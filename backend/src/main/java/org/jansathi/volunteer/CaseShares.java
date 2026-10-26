package org.jansathi.volunteer;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jansathi.account.Accounts;
import org.jansathi.account.Accounts.User;
import org.jansathi.account.Crypto;
import org.jansathi.web.BadRequestException;
import org.jansathi.web.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Consent-scoped, time-limited access for helpers (brief §6.6, docs/PRIVACY_AND_SECURITY.md §4).
 * A person shares their case with a short code they read out to a helper, or asks for help, which
 * puts the case in the helpers' queue with nothing but its language. Either way they choose what a
 * helper may see, the access ends by itself, they can end it any time, and every helper action is
 * written to the person's audit log.
 */
@Service
public class CaseShares {

	/** What a helper may see. */
	public static final Set<String> SCOPES = Set.of("profile", "checklist", "conversation");
	/** No 0/O, 1/I/L: easy to read out on the phone. */
	private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
	private static final Pattern PERSONAL_NUMBER = Pattern.compile("\\d(?:[\\s-]?\\d){8,}");
	static final Duration MAX_DURATION = Duration.ofDays(7);
	static final int MAX_NOTE = 1000;

	private final JdbcClient jdbc;
	private final Crypto crypto;
	private final Accounts accounts;

	public CaseShares(JdbcClient jdbc, Crypto crypto, Accounts accounts) {
		this.jdbc = jdbc;
		this.crypto = crypto;
		this.accounts = accounts;
	}

	/**
	 * @param helper the helper's label once someone has joined; null while waiting
	 * @param code returned once, when a code share is created; never stored in clear
	 */
	public record Share(String id, Set<String> scopes, boolean helpRequest, Instant createdAt, Instant expiresAt, String helper, String code) {}

	private static OffsetDateTime now() {
		return OffsetDateTime.now(ZoneOffset.UTC);
	}

	private static String newCode() {
		StringBuilder s = new StringBuilder();
		byte[] r = Crypto.randomBytes(6);
		for (byte b : r) {
			s.append(CODE_ALPHABET.charAt(Math.floorMod(b, CODE_ALPHABET.length())));
		}
		return s.toString();
	}

	private String codeHash(String code) {
		return crypto.keyedHash("share:" + code.strip().toUpperCase().replaceAll("[\\s-]", ""));
	}

	static Set<String> parseScopes(List<String> scopes) {
		Set<String> out = new LinkedHashSet<>();
		for (String s : scopes == null ? List.<String>of() : scopes) {
			if (!SCOPES.contains(s)) {
				throw new BadRequestException("Unknown scope: " + s);
			}
			out.add(s);
		}
		if (out.isEmpty()) {
			out.add("profile");
		}
		return out;
	}

	@Transactional
	public Share create(User user, List<String> scopes, Duration duration, boolean helpRequest, String lang) {
		Set<String> sc = parseScopes(scopes);
		Duration d = duration == null || duration.isNegative() || duration.isZero() ? Duration.ofDays(1)
				: duration.compareTo(MAX_DURATION) > 0 ? MAX_DURATION : duration;
		String id = UUID.randomUUID().toString();
		String code = helpRequest ? null : newCode();
		OffsetDateTime now = now();
		jdbc.sql("""
				INSERT INTO case_share (id, user_id, code_hash, scopes, help_request, lang, created_at, expires_at)
				VALUES (:id, :u, :c, :s, :h, :l, :now, :exp)
				""")
				.param("id", id).param("u", user.id()).param("c", code == null ? null : codeHash(code)).param("s", String.join(",", sc))
				.param("h", helpRequest).param("l", lang).param("now", now).param("exp", now.plus(d)).update();
		accounts.audit(user.id(), user.id(), "You", helpRequest ? "asked_for_help" : "shared_case", "Can see: " + String.join(", ", sc));
		return new Share(id, sc, helpRequest, now.toInstant(), now.plus(d).toInstant(), null, code);
	}

	private Share row(java.sql.ResultSet rs) throws java.sql.SQLException {
		String vid = rs.getString("volunteer_id");
		String helper = vid == null ? null : accounts.find(vid).map(User::label).orElse("Helper");
		return new Share(rs.getString("id"), new LinkedHashSet<>(List.of(rs.getString("scopes").split(","))), rs.getBoolean("help_request"),
				rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getObject("expires_at", OffsetDateTime.class).toInstant(), helper, null);
	}

	/** The person's active shares. */
	public List<Share> activeFor(User user) {
		return jdbc.sql("SELECT * FROM case_share WHERE user_id = :u AND revoked_at IS NULL AND expires_at > :now ORDER BY created_at DESC")
				.param("u", user.id()).param("now", now()).query((rs, i) -> row(rs)).list();
	}

	public void revoke(User user, String id) {
		int n = jdbc.sql("UPDATE case_share SET revoked_at = :now WHERE id = :id AND user_id = :u AND revoked_at IS NULL")
				.param("now", now()).param("id", id).param("u", user.id()).update();
		if (n == 0) {
			throw new NotFoundException("No active share with id " + id);
		}
		accounts.audit(user.id(), user.id(), "You", "ended_sharing", null);
	}

	// ---------- helper side ----------

	/** A case in a helper's list: no personal details, only what is needed to pick it up. */
	public record CaseSummary(String id, String lang, Set<String> scopes, boolean helpRequest, Instant createdAt, Instant expiresAt) {}

	static void requireVolunteer(User user) {
		if (!user.isVolunteer()) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only registered helpers can do this");
		}
	}

	private CaseSummary summary(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new CaseSummary(rs.getString("id"), rs.getString("lang"), new LinkedHashSet<>(List.of(rs.getString("scopes").split(","))),
				rs.getBoolean("help_request"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
				rs.getObject("expires_at", OffsetDateTime.class).toInstant());
	}

	/** Help requests nobody has picked up yet. */
	public List<CaseSummary> openRequests(User helper) {
		requireVolunteer(helper);
		return jdbc.sql("""
				SELECT * FROM case_share WHERE help_request = TRUE AND volunteer_id IS NULL AND revoked_at IS NULL AND expires_at > :now
				ORDER BY created_at
				""").param("now", now()).query((rs, i) -> summary(rs)).list();
	}

	/** Cases this helper is working on. */
	public List<CaseSummary> casesOf(User helper) {
		requireVolunteer(helper);
		return jdbc.sql("SELECT * FROM case_share WHERE volunteer_id = :v AND revoked_at IS NULL AND expires_at > :now ORDER BY claimed_at DESC")
				.param("v", helper.id()).param("now", now()).query((rs, i) -> summary(rs)).list();
	}

	@Transactional
	public CaseSummary joinByCode(User helper, String code) {
		requireVolunteer(helper);
		if (code == null || code.isBlank()) {
			throw new BadRequestException("Enter the code the person gave you");
		}
		String id = jdbc.sql("""
				SELECT id FROM case_share WHERE code_hash = :c AND revoked_at IS NULL AND expires_at > :now
				AND (volunteer_id IS NULL OR volunteer_id = :v)
				""").param("c", codeHash(code)).param("now", now()).param("v", helper.id()).query(String.class).optional()
				.orElseThrow(() -> new NotFoundException("No active case with this code"));
		return claim(helper, id);
	}

	@Transactional
	public CaseSummary pickUp(User helper, String id) {
		requireVolunteer(helper);
		return claim(helper, id);
	}

	private CaseSummary claim(User helper, String id) {
		int n = jdbc.sql("""
				UPDATE case_share SET volunteer_id = :v, claimed_at = :now
				WHERE id = :id AND revoked_at IS NULL AND expires_at > :now AND (volunteer_id IS NULL OR volunteer_id = :v)
				""").param("v", helper.id()).param("now", now()).param("id", id).update();
		if (n == 0) {
			throw new NotFoundException("This case is no longer available");
		}
		accounts.audit(ownerOf(id), helper.id(), helper.label(), "helper_joined", null);
		return jdbc.sql("SELECT * FROM case_share WHERE id = :id").param("id", id).query((rs, i) -> summary(rs)).single();
	}

	/** Ends this helper's work on a case; the person can share again later. */
	public void leave(User helper, String id) {
		requireVolunteer(helper);
		String owner = ownerOf(id);
		int n = jdbc.sql("UPDATE case_share SET revoked_at = :now WHERE id = :id AND volunteer_id = :v AND revoked_at IS NULL")
				.param("now", now()).param("id", id).param("v", helper.id()).update();
		if (n > 0) {
			accounts.audit(owner, helper.id(), helper.label(), "helper_left", null);
		}
	}

	private String ownerOf(String shareId) {
		return jdbc.sql("SELECT user_id FROM case_share WHERE id = :id").param("id", shareId).query(String.class).optional()
				.orElseThrow(() -> new NotFoundException("No case with id " + shareId));
	}

	/** An active share this helper holds: the owner and what they may see. */
	public record Access(String shareId, String ownerId, Set<String> scopes) {}

	public Access access(User helper, String shareId) {
		requireVolunteer(helper);
		return jdbc.sql("SELECT * FROM case_share WHERE id = :id AND volunteer_id = :v AND revoked_at IS NULL AND expires_at > :now")
				.param("id", shareId).param("v", helper.id()).param("now", now())
				.query((rs, i) -> new Access(rs.getString("id"), rs.getString("user_id"), new LinkedHashSet<>(List.of(rs.getString("scopes").split(",")))))
				.optional()
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "You don't have access to this case (it may have ended)"));
	}

	// ---------- notes from helpers ----------

	public record Note(String id, String helper, String text, Instant createdAt) {}

	public Note addNote(User helper, String shareId, String text) {
		Access a = access(helper, shareId);
		String clean = text == null ? "" : PERSONAL_NUMBER.matcher(text.strip()).replaceAll("[number removed]");
		if (clean.isEmpty() || clean.length() > MAX_NOTE) {
			throw new BadRequestException("A note needs 1 to " + MAX_NOTE + " characters");
		}
		String id = UUID.randomUUID().toString();
		OffsetDateTime now = now();
		jdbc.sql("INSERT INTO case_note (id, share_id, volunteer_id, content_enc, created_at) VALUES (:id, :s, :v, :c, :now)")
				.param("id", id).param("s", shareId).param("v", helper.id()).param("c", crypto.encrypt(clean)).param("now", now).update();
		accounts.audit(a.ownerId(), helper.id(), helper.label(), "helper_note", null);
		return new Note(id, helper.label(), clean, now.toInstant());
	}

	/** Notes on one case (helper side) or on all of a person's cases (owner side). */
	public List<Note> notes(String shareId, String ownerId) {
		String where = shareId != null ? "n.share_id = :k" : "s.user_id = :k";
		List<Note> out = new ArrayList<>();
		jdbc.sql("SELECT n.*, s.user_id FROM case_note n JOIN case_share s ON s.id = n.share_id WHERE " + where + " ORDER BY n.created_at")
				.param("k", shareId != null ? shareId : ownerId)
				.query((rs, i) -> {
					String vid = rs.getString("volunteer_id");
					return new Note(rs.getString("id"), vid == null ? "Helper" : accounts.find(vid).map(User::label).orElse("Helper"),
							crypto.decrypt(rs.getString("content_enc")), rs.getObject("created_at", OffsetDateTime.class).toInstant());
				})
				.list()
				.forEach(out::add);
		return out;
	}

}
