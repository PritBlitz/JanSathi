package org.jansathi.volunteer;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jansathi.account.Accounts;
import org.jansathi.account.Accounts.User;
import org.jansathi.account.Crypto;
import org.jansathi.web.BadRequestException;
import org.jansathi.web.NotFoundException;
import org.jansathi.web.Scrub;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reporting a helper (docs/PRIVACY_AND_SECURITY.md §4.5). A person may report a helper who joined
 * one of their cases, even after it ended. The report names the helper but not the person, and the
 * person's note is encrypted with digit runs removed.
 */
@Service
public class HelperReports {

	public static final Set<String> REASONS = Set.of("asked_for_payment", "misused_information", "pretended_to_be_official", "rude", "other");
	static final int MAX_NOTE = 1000;

	public record Report(
			String id, Instant receivedAt, String helperId, String helper, String reason, String note, String status, String resolution, Instant resolvedAt, String resolvedBy) {}

	private final JdbcClient jdbc;
	private final Crypto crypto;
	private final Accounts accounts;

	public HelperReports(JdbcClient jdbc, Crypto crypto, Accounts accounts) {
		this.jdbc = jdbc;
		this.crypto = crypto;
		this.accounts = accounts;
	}

	private static OffsetDateTime now() {
		return OffsetDateTime.now(ZoneOffset.UTC);
	}

	/** @return the report id */
	@Transactional
	public String file(User user, String shareId, String reason, String note) {
		if (reason == null || !REASONS.contains(reason)) {
			throw new BadRequestException("reason must be one of " + REASONS);
		}
		String clean = note == null ? "" : Scrub.numbers(note.strip());
		if (clean.length() > MAX_NOTE) {
			throw new BadRequestException("A note can have at most " + MAX_NOTE + " characters");
		}
		String helperId = jdbc.sql("SELECT volunteer_id FROM case_share WHERE id = :id AND user_id = :u AND volunteer_id IS NOT NULL")
				.param("id", shareId).param("u", user.id()).query(String.class).optional()
				.orElseThrow(() -> new NotFoundException("No helper on this case"));
		String id = UUID.randomUUID().toString();
		jdbc.sql("""
				INSERT INTO helper_report (id, received_at, helper_id, helper_label, reason, note_enc)
				VALUES (:id, :now, :h, :l, :r, :n)
				""")
				.param("id", id).param("now", now()).param("h", helperId)
				.param("l", accounts.find(helperId).map(User::label).orElse("Helper")).param("r", reason)
				.param("n", clean.isEmpty() ? null : crypto.encrypt(clean)).update();
		accounts.audit(user.id(), user.id(), "You", "reported_helper", null);
		return id;
	}

	private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
		OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
		return t == null ? null : t.toInstant();
	}

	/** Oldest first, so nothing waits forever; {@code status} null lists every report. */
	public List<Report> list(String status, int limit) {
		var spec = jdbc.sql("SELECT * FROM helper_report " + (status == null ? "" : "WHERE status = :s ") + "ORDER BY received_at LIMIT :n").param("n", limit);
		if (status != null) {
			spec = spec.param("s", status);
		}
		return spec.query((rs, i) -> {
			String note = rs.getString("note_enc");
			return new Report(rs.getString("id"), instant(rs, "received_at"), rs.getString("helper_id"), rs.getString("helper_label"), rs.getString("reason"),
					note == null ? null : crypto.decrypt(note), rs.getString("status"), rs.getString("resolution"), instant(rs, "resolved_at"), rs.getString("resolved_by"));
		}).list();
	}

	/**
	 * Closes an open report.
	 *
	 * @param deactivate also ends the helper's role and every case they hold
	 * @return false when no open report has this id
	 */
	@Transactional
	public boolean resolve(String id, String status, String resolution, boolean deactivate, String by) {
		int n = jdbc.sql("""
				UPDATE helper_report SET status = :s, resolution = :r, resolved_at = :now, resolved_by = :by
				WHERE id = :id AND status = 'open'
				""").param("s", status).param("r", resolution).param("now", now()).param("by", by).param("id", id).update();
		if (n == 0) {
			return false;
		}
		if (deactivate) {
			jdbc.sql("SELECT helper_id FROM helper_report WHERE id = :id AND helper_id IS NOT NULL").param("id", id).query(String.class).optional()
					.ifPresent(helper -> accounts.deactivateHelper(helper, "reported"));
		}
		return true;
	}
}
