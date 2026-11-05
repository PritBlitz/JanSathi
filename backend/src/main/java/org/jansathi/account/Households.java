package org.jansathi.account;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jansathi.content.BundleRepository;
import org.jansathi.web.BadRequestException;
import org.jansathi.web.OnDeviceOnlyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A person's household, stored encrypted: the same JSON the PWA keeps on the device
 * ({@code {members: [{id, name, profile, skipped, history}], active, checklist, applications}}). Only
 * fields from fields.json are accepted in profiles, so nothing else (an Aadhaar number, say) can be saved.
 */
@Service
public class Households {

	static final int MAX_BYTES = 64_000;
	static final int MAX_MEMBERS = 12;
	static final int MAX_REASON = 300;
	/** Manual application tracking (brief §6.7). */
	static final Set<String> APPLICATION_STATUSES = Set.of("planned", "applied", "under_review", "approved", "rejected");
	private static final Pattern PERSONAL_NUMBER = Pattern.compile("\\d(?:[\\s-]?\\d){8,}");

	private final JdbcClient jdbc;
	private final Crypto crypto;
	private final ObjectMapper json;
	private final BundleRepository bundles;
	private final Accounts accounts;

	public Households(JdbcClient jdbc, Crypto crypto, ObjectMapper json, BundleRepository bundles, Accounts accounts) {
		this.jdbc = jdbc;
		this.crypto = crypto;
		this.json = json;
		this.bundles = bundles;
		this.accounts = accounts;
	}

	/** The saved household, or a new one with a single member. */
	public ObjectNode get(String userId) {
		return jdbc.sql("SELECT payload_enc FROM user_data WHERE user_id = :u").param("u", userId).query(String.class).optional()
				.map(enc -> (ObjectNode) json.readTree(crypto.decrypt(enc)))
				.orElseGet(Households::empty);
	}

	public static ObjectNode empty() {
		ObjectNode h = JsonNodeFactory.instance.objectNode();
		ObjectNode m = h.putArray("members").addObject();
		m.put("id", "me");
		m.putNull("name");
		m.putObject("profile");
		m.putArray("skipped");
		m.putArray("history");
		h.put("active", "me");
		ObjectNode c = h.putObject("checklist");
		c.putArray("schemes");
		c.putObject("have");
		return h;
	}

	public ObjectNode put(String userId, JsonNode household) {
		ObjectNode clean = validate(household);
		if (isUnder18(holder(clean))) {
			keepOnDeviceOnly(userId);
			throw new OnDeviceOnlyException();
		}
		jdbc.sql("UPDATE app_user SET on_device_only = FALSE WHERE id = :u AND on_device_only = TRUE").param("u", userId).update();
		String plain = json.writeValueAsString(clean);
		if (plain.length() > MAX_BYTES) {
			throw new BadRequestException("Saved data is too large");
		}
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		int updated = jdbc.sql("UPDATE user_data SET payload_enc = :p, updated_at = :now WHERE user_id = :u")
				.param("p", crypto.encrypt(plain)).param("now", now).param("u", userId).update();
		if (updated == 0) {
			jdbc.sql("INSERT INTO user_data (user_id, payload_enc, updated_at) VALUES (:u, :p, :now)")
					.param("u", userId).param("p", crypto.encrypt(plain)).param("now", now).update();
		}
		return clean;
	}

	/** The account holder: the member the app calls "me", or else the first. A child added by a parent is not. */
	static JsonNode holder(ObjectNode household) {
		for (JsonNode m : household.path("members")) {
			if ("me".equals(m.path("id").asString(""))) {
				return m;
			}
		}
		return household.path("members").get(0);
	}

	/** True for the band {@code under_18} or an exact age below 18. An unanswered age is not under 18. */
	static boolean isUnder18(JsonNode member) {
		JsonNode age = member.path("profile").path("age");
		return age.isNumber() ? age.asInt() < 18 : "under_18".equals(age.asString(""));
	}

	/**
	 * PRIVACY §6: there is no verifiable guardian consent, so someone under 18 uses the app on their
	 * device only. Whatever the server holds goes now, and the flag stops sharing, reminders and
	 * Saathi. Answering with an adult age clears the flag (the answer is the person's own; see §6).
	 */
	private void keepOnDeviceOnly(String userId) {
		jdbc.sql("UPDATE app_user SET on_device_only = TRUE WHERE id = :u").param("u", userId).update();
		jdbc.sql("DELETE FROM user_data WHERE user_id = :u").param("u", userId).update();
		jdbc.sql("DELETE FROM guide_message WHERE user_id = :u").param("u", userId).update();
		jdbc.sql("DELETE FROM push_subscription WHERE user_id = :u").param("u", userId).update();
		jdbc.sql("DELETE FROM reminder_channel WHERE user_id = :u").param("u", userId).update();
		jdbc.sql("DELETE FROM telegram_link WHERE user_id = :u").param("u", userId).update();
		jdbc.sql("UPDATE case_share SET revoked_at = :now WHERE user_id = :u AND revoked_at IS NULL")
				.param("now", OffsetDateTime.now(ZoneOffset.UTC)).param("u", userId).update();
		accounts.audit(userId, userId, "You", "on_device_only", null);
	}

	/** Keeps only the known shape; rejects unknown profile fields. */
	ObjectNode validate(JsonNode h) {
		if (h == null || !h.isObject() || !h.path("members").isArray() || h.path("members").isEmpty()) {
			throw new BadRequestException("A household needs at least one member");
		}
		if (h.path("members").size() > MAX_MEMBERS) {
			throw new BadRequestException("At most " + MAX_MEMBERS + " family members");
		}
		var fields = bundles.get().fields();
		ObjectNode out = JsonNodeFactory.instance.objectNode();
		ArrayNode members = out.putArray("members");
		for (JsonNode m : h.path("members")) {
			ObjectNode mm = members.addObject();
			mm.put("id", m.path("id").asString("me"));
			String name = m.path("name").isString() ? m.get("name").asString() : null;
			if (name != null && name.length() > 40) {
				throw new BadRequestException("A name can have at most 40 characters");
			}
			if (name == null) {
				mm.putNull("name");
			} else {
				mm.put("name", name);
			}
			ObjectNode profile = mm.putObject("profile");
			for (Map.Entry<String, JsonNode> e : m.path("profile").properties()) {
				if (!fields.isKnown(e.getKey())) {
					throw new BadRequestException("Unknown profile field: " + e.getKey());
				}
				profile.set(e.getKey(), e.getValue());
			}
			ArrayNode skipped = mm.putArray("skipped");
			m.path("skipped").forEach(f -> {
				if (fields.isKnown(f.asString(""))) {
					skipped.add(f.asString());
				}
			});
			mm.set("history", m.path("history").isArray() ? m.get("history") : JsonNodeFactory.instance.arrayNode());
		}
		out.put("active", h.path("active").asString(members.get(0).path("id").asString()));
		ObjectNode checklist = out.putObject("checklist");
		ArrayNode schemes = checklist.putArray("schemes");
		h.path("checklist").path("schemes").forEach(s -> {
			if (bundles.get().scheme(s.asString("")).isPresent()) {
				schemes.add(s.asString());
			}
		});
		ObjectNode have = checklist.putObject("have");
		h.path("checklist").path("have").properties().forEach(e -> {
			if (e.getValue().isBoolean()) {
				have.put(e.getKey(), e.getValue().booleanValue());
			}
		});
		if (h.path("applications").isObject()) {
			ObjectNode applications = out.putObject("applications");
			h.path("applications").properties().forEach(e -> {
				if (bundles.get().scheme(e.getKey()).isPresent() && validApplication(e.getValue())) {
					ObjectNode a = applications.putObject(e.getKey());
					a.put("status", e.getValue().get("status").asString());
					JsonNode reason = e.getValue().path("reason");
					if (reason.isString() && !reason.asString().isBlank()) {
						a.put("reason", cleanReason(reason.asString()));
					} else {
						a.putNull("reason");
					}
					a.put("updated", e.getValue().get("updated").asString());
				}
			});
		}
		return out;
	}

	private static boolean validApplication(JsonNode a) {
		if (!APPLICATION_STATUSES.contains(a.path("status").asString(""))) {
			return false;
		}
		try {
			LocalDate.parse(a.path("updated").asString(""));
			return true;
		} catch (DateTimeParseException e) {
			return false;
		}
	}

	/** A reason is the person's own words, so a pasted Aadhaar or account number is taken out. */
	private static String cleanReason(String reason) {
		String clean = PERSONAL_NUMBER.matcher(reason.strip()).replaceAll("[number removed]");
		return clean.length() > MAX_REASON ? clean.substring(0, MAX_REASON) : clean;
	}

	/** The active member (or the first). */
	public static ObjectNode activeMember(ObjectNode household) {
		String active = household.path("active").asString("");
		for (JsonNode m : household.path("members")) {
			if (m.path("id").asString("").equals(active)) {
				return (ObjectNode) m;
			}
		}
		return (ObjectNode) household.path("members").get(0);
	}
}
