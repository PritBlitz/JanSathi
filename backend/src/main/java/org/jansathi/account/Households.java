package org.jansathi.account;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.jansathi.content.BundleRepository;
import org.jansathi.web.BadRequestException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A person's household, stored encrypted: the same JSON the PWA keeps on the device
 * ({@code {members: [{id, name, profile, skipped, history}], active, checklist}}). Only fields from
 * fields.json are accepted in profiles, so nothing else (an Aadhaar number, say) can be saved.
 */
@Service
public class Households {

	static final int MAX_BYTES = 64_000;
	static final int MAX_MEMBERS = 12;

	private final JdbcClient jdbc;
	private final Crypto crypto;
	private final ObjectMapper json;
	private final BundleRepository bundles;

	public Households(JdbcClient jdbc, Crypto crypto, ObjectMapper json, BundleRepository bundles) {
		this.jdbc = jdbc;
		this.crypto = crypto;
		this.json = json;
		this.bundles = bundles;
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
		return out;
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
