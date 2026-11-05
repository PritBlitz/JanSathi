package org.jansathi.admin;

import java.util.List;
import java.util.Map;
import org.jansathi.account.Crypto;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The middle step of rotating {@code JANSATHI_SECRET} (docs/OPERATIONS.md §6): with the new secret in
 * place and the old one in {@code JANSATHI_SECRET_PREVIOUS}, re-encrypts every encrypted column under
 * the new key. Phone hashes can't be redone here, because the number isn't stored: each one moves at
 * that person's next sign-in. Safe to run again. A new encrypted column must be added to this list.
 */
@RestController
@RequestMapping("/api/v1/admin/maintenance")
public class KeyRotation {

	private record Column(String table, String key, String column) {}

	private static final List<Column> ENCRYPTED = List.of(
			new Column("user_data", "user_id", "payload_enc"),
			new Column("guide_message", "id", "content_enc"),
			new Column("case_note", "id", "content_enc"),
			new Column("push_subscription", "user_id", "endpoint_enc"),
			new Column("staff_account", "id", "totp_secret_enc"),
			new Column("grievance", "id", "message_enc"),
			new Column("grievance", "id", "contact_enc"),
			new Column("helper_report", "id", "note_enc"));

	private final JdbcClient jdbc;
	private final Crypto crypto;

	public KeyRotation(JdbcClient jdbc, Crypto crypto) {
		this.jdbc = jdbc;
		this.crypto = crypto;
	}

	public record Result(int rows) {}

	@PostMapping("/re-encrypt")
	public Result reEncrypt() {
		int rows = 0;
		for (Column c : ENCRYPTED) {
			List<Map.Entry<String, String>> values = jdbc.sql("SELECT " + c.key() + ", " + c.column() + " FROM " + c.table() + " WHERE " + c.column() + " IS NOT NULL")
					.query((rs, i) -> Map.entry(rs.getString(1), rs.getString(2))).list();
			for (Map.Entry<String, String> v : values) {
				jdbc.sql("UPDATE " + c.table() + " SET " + c.column() + " = :v WHERE " + c.key() + " = :k")
						.param("v", crypto.encrypt(crypto.decrypt(v.getValue()))).param("k", v.getKey()).update();
				rows++;
			}
		}
		// Addresses: the hash that finds a Telegram chat is redone from the decrypted address.
		record Address(String user, String channel, String enc) {}
		List<Address> addresses = jdbc.sql("SELECT user_id, channel, address_enc FROM reminder_channel")
				.query((rs, i) -> new Address(rs.getString(1), rs.getString(2), rs.getString(3))).list();
		for (Address a : addresses) {
			String plain = crypto.decrypt(a.enc());
			jdbc.sql("UPDATE reminder_channel SET address_enc = :e, address_hash = :h WHERE user_id = :u AND channel = :c")
					.param("e", crypto.encrypt(plain)).param("h", crypto.keyedHash(a.channel() + ":" + plain)).param("u", a.user()).param("c", a.channel()).update();
			rows++;
		}
		return new Result(rows);
	}
}
