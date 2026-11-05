package org.jansathi.report;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jansathi.account.Accounts;
import org.jansathi.account.Crypto;
import org.jansathi.content.Languages;
import org.jansathi.web.BadRequestException;
import org.jansathi.web.Scrub;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Grievance redressal (docs/PRIVACY_AND_SECURITY.md §6). Anyone can file one, signed in or not, and
 * nothing links it to an account. The reference is an unguessable id, which is also how the person
 * reads the answer, so a contact is optional. Message and contact are encrypted.
 */
@Service
public class Grievances {

	static final Set<String> CATEGORIES = Set.of("privacy", "data_deletion", "helper_conduct", "wrong_information", "accessibility", "other");
	static final int MAX_MESSAGE = 2000;
	static final int MAX_RESPONSE = 1000;
	private static final Pattern EMAIL = Pattern.compile("[^\\s@]{1,64}@[^\\s@]{1,190}\\.[^\\s@]{2,}");

	/** What moderators see. */
	public record Grievance(
			String id, Instant receivedAt, String category, String message, String contact, String lang, String status, String response, Instant resolvedAt, String resolvedBy) {}

	/** What the person sees with their reference. */
	public record Status(String id, String status, Instant receivedAt, Instant resolvedAt, String response) {}

	private final JdbcClient jdbc;
	private final Crypto crypto;

	public Grievances(JdbcClient jdbc, Crypto crypto) {
		this.jdbc = jdbc;
		this.crypto = crypto;
	}

	private static OffsetDateTime now() {
		return OffsetDateTime.now(ZoneOffset.UTC);
	}

	private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
		OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
		return t == null ? null : t.toInstant();
	}

	/** A mobile number or e-mail address, or null. Nothing else, so this can't hold an Aadhaar number. */
	static String cleanContact(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String c = raw.strip();
		return EMAIL.matcher(c).matches() && c.length() <= 200 ? c : Accounts.normalisePhone(c);
	}

	public String file(String category, String message, String contact, String lang) {
		if (category == null || !CATEGORIES.contains(category)) {
			throw new BadRequestException("category must be one of " + CATEGORIES);
		}
		String text = message == null ? "" : Scrub.numbers(message.strip());
		if (text.isEmpty() || text.length() > MAX_MESSAGE) {
			throw new BadRequestException("A grievance needs 1 to " + MAX_MESSAGE + " characters");
		}
		String reach = cleanContact(contact);
		String id = UUID.randomUUID().toString();
		jdbc.sql("""
				INSERT INTO grievance (id, received_at, category, message_enc, contact_enc, lang)
				VALUES (:id, :now, :c, :m, :r, :l)
				""")
				.param("id", id).param("now", now()).param("c", category).param("m", crypto.encrypt(text))
				.param("r", reach == null ? null : crypto.encrypt(reach)).param("l", Languages.orEnglish(lang)).update();
		return id;
	}

	public Optional<Status> status(String id) {
		return jdbc.sql("SELECT * FROM grievance WHERE id = :id").param("id", id)
				.query((rs, i) -> new Status(rs.getString("id"), rs.getString("status"), instant(rs, "received_at"), instant(rs, "resolved_at"), rs.getString("response")))
				.optional();
	}

	/** Oldest first: the law counts from the day it arrived. */
	public List<Grievance> list(String status, int limit) {
		var spec = jdbc.sql("SELECT * FROM grievance " + (status == null ? "" : "WHERE status = :s ") + "ORDER BY received_at LIMIT :n").param("n", limit);
		if (status != null) {
			spec = spec.param("s", status);
		}
		return spec.query((rs, i) -> {
			String contact = rs.getString("contact_enc");
			return new Grievance(rs.getString("id"), instant(rs, "received_at"), rs.getString("category"), crypto.decrypt(rs.getString("message_enc")),
					contact == null ? null : crypto.decrypt(contact), rs.getString("lang"), rs.getString("status"), rs.getString("response"),
					instant(rs, "resolved_at"), rs.getString("resolved_by"));
		}).list();
	}

	public boolean resolve(String id, String status, String response, String by) {
		return jdbc.sql("""
				UPDATE grievance SET status = :s, response = :r, resolved_at = :now, resolved_by = :by
				WHERE id = :id AND status = 'open'
				""").param("s", status).param("r", response).param("now", now()).param("by", by).param("id", id).update() == 1;
	}
}
