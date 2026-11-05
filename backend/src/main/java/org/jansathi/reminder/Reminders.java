package org.jansathi.reminder;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jansathi.account.Accounts;
import org.jansathi.account.Crypto;
import org.jansathi.account.Households;
import org.jansathi.content.BundleRepository;
import org.jansathi.content.ContentBundle;
import org.jansathi.web.BadRequestException;
import org.jansathi.web.ErrorReporter;
import org.jansathi.web.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reminders for unfinished checklists and coming deadlines (brief §6.5), over whichever channels the
 * person turned on. Every send is claimed in {@code reminder_sent} first, so a deadline reminds once
 * per channel, even with two instances, and a failed send gives the claim back for the next run.
 */
@Service
public class Reminders {

	private static final Logger log = LoggerFactory.getLogger(Reminders.class);
	private static final int MAX_ENDPOINT = 1000;
	private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
	static final int DEADLINE_WARN_DAYS = 14;
	static final Duration TELEGRAM_LINK_TTL = Duration.ofMinutes(15);
	/** The server posts to this address, so only the browsers' own push services are accepted. */
	private static final List<String> PUSH_HOSTS = List.of(
			"fcm.googleapis.com", ".push.services.mozilla.com", ".push.apple.com", ".notify.windows.com");

	private final JdbcClient jdbc;
	private final Crypto crypto;
	private final Households households;
	private final BundleRepository bundles;
	private final PushSender sender;
	private final Accounts accounts;
	private final Map<String, ReminderChannel> channels;
	private final ErrorReporter errors;
	private final MeterRegistry meters;

	public Reminders(JdbcClient jdbc, Crypto crypto, Households households, BundleRepository bundles, PushSender sender, Accounts accounts,
			List<ReminderChannel> channels, ErrorReporter errors, MeterRegistry meters) {
		this.jdbc = jdbc;
		this.crypto = crypto;
		this.households = households;
		this.bundles = bundles;
		this.sender = sender;
		this.accounts = accounts;
		this.channels = channels.stream().collect(Collectors.toMap(ReminderChannel::id, Function.identity()));
		this.errors = errors;
		this.meters = meters;
	}

	private static OffsetDateTime now() {
		return OffsetDateTime.now(ZoneOffset.UTC);
	}

	/** What this server can offer, in a stable order. */
	public List<String> configuredChannels() {
		return List.of("web_push", "telegram", "sms", "whatsapp").stream()
				.filter(id -> channels.containsKey(id) && channels.get(id).configured()).toList();
	}

	private ReminderChannel channel(String id) {
		ReminderChannel c = channels.get(id);
		if (c == null) {
			throw new NotFoundException("No reminder channel called " + id);
		}
		if (!c.configured()) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "This reminder channel is switched off");
		}
		return c;
	}

	// ---------- choosing channels ----------

	static boolean isPushService(String endpoint) {
		if (endpoint == null || endpoint.length() > MAX_ENDPOINT) {
			return false;
		}
		try {
			URI uri = new URI(endpoint);
			String host = uri.getHost();
			if (!"https".equals(uri.getScheme()) || host == null || uri.getPort() != -1 || uri.getUserInfo() != null) {
				return false;
			}
			String h = host.toLowerCase(Locale.ROOT);
			return PUSH_HOSTS.stream().anyMatch(p -> p.startsWith(".") ? h.endsWith(p) : h.equals(p));
		} catch (java.net.URISyntaxException e) {
			return false;
		}
	}

	/** Replaces this person's subscription: reminders go to the browser that asked last. */
	public void subscribe(String userId, String endpoint) {
		if (!sender.enabled()) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Reminders are switched off");
		}
		accounts.requireNotOnDeviceOnly(userId);
		if (!isPushService(endpoint)) {
			throw new BadRequestException("Not a push service address");
		}
		String enc = crypto.encrypt(endpoint);
		OffsetDateTime now = now();
		int n = jdbc.sql("UPDATE push_subscription SET endpoint_enc = :e WHERE user_id = :u").param("e", enc).param("u", userId).update();
		if (n == 0) {
			jdbc.sql("INSERT INTO push_subscription (user_id, endpoint_enc, created_at) VALUES (:u, :e, :now)")
					.param("u", userId).param("e", enc).param("now", now).update();
		}
	}

	public void unsubscribe(String userId) {
		jdbc.sql("DELETE FROM push_subscription WHERE user_id = :u").param("u", userId).update();
	}

	private void save(String userId, String channel, String address) {
		String enc = crypto.encrypt(address);
		String hash = crypto.keyedHash(channel + ":" + address);
		int n = jdbc.sql("UPDATE reminder_channel SET address_enc = :e, address_hash = :h WHERE user_id = :u AND channel = :c")
				.param("e", enc).param("h", hash).param("u", userId).param("c", channel).update();
		if (n == 0) {
			jdbc.sql("INSERT INTO reminder_channel (user_id, channel, address_enc, address_hash, created_at) VALUES (:u, :c, :e, :h, :now)")
					.param("u", userId).param("c", channel).param("e", enc).param("h", hash).param("now", now()).update();
		}
	}

	/**
	 * Turns a channel on.
	 *
	 * @param body web_push: the browser subscription; sms and whatsapp: {@code {phone}}, which must be
	 *     the number the person signed in with, so nobody can send reminders to someone else;
	 *     telegram: nothing, it is on once the chat is linked
	 */
	public void enable(String userId, String channelId, JsonNode body) {
		channel(channelId);
		accounts.requireNotOnDeviceOnly(userId);
		switch (channelId) {
			case "web_push" -> subscribe(userId, body == null ? null : body.path("endpoint").asString(null));
			case "telegram" -> {
				if (!isOn(userId, "telegram")) {
					throw new ResponseStatusException(HttpStatus.CONFLICT, "Link Telegram first");
				}
			}
			default -> {
				String phone = Accounts.normalisePhone(body == null ? null : body.path("phone").asString(null));
				if (!accounts.ownsPhone(userId, phone)) {
					throw new BadRequestException("Reminders can go only to the number you signed in with");
				}
				save(userId, channelId, phone);
			}
		}
		accounts.audit(userId, userId, "You", "reminders_on", channelId);
	}

	private boolean isOn(String userId, String channel) {
		return jdbc.sql("SELECT COUNT(*) FROM reminder_channel WHERE user_id = :u AND channel = :c")
				.param("u", userId).param("c", channel).query(Integer.class).single() > 0;
	}

	public void disable(String userId, String channelId) {
		if (!channels.containsKey(channelId)) {
			throw new NotFoundException("No reminder channel called " + channelId);
		}
		if ("web_push".equals(channelId)) {
			unsubscribe(userId);
		} else {
			jdbc.sql("DELETE FROM reminder_channel WHERE user_id = :u AND channel = :c").param("u", userId).param("c", channelId).update();
		}
		accounts.audit(userId, userId, "You", "reminders_off", channelId);
	}

	public record ChannelState(String channel, Instant since, Instant lastSentAt) {}

	private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
		OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
		return t == null ? null : t.toInstant();
	}

	/** The channels this person has on. Addresses are not returned. */
	public List<ChannelState> enabled(String userId) {
		List<ChannelState> out = new ArrayList<>(jdbc.sql("SELECT created_at, last_sent_at FROM push_subscription WHERE user_id = :u").param("u", userId)
				.query((rs, i) -> new ChannelState("web_push", instant(rs, "created_at"), instant(rs, "last_sent_at"))).list());
		out.addAll(jdbc.sql("SELECT channel, created_at, last_sent_at FROM reminder_channel WHERE user_id = :u ORDER BY channel").param("u", userId)
				.query((rs, i) -> new ChannelState(rs.getString("channel"), instant(rs, "created_at"), instant(rs, "last_sent_at"))).list());
		return out;
	}

	// ---------- linking Telegram ----------

	/** A one-time link. Only its hash is kept, and it lasts 15 minutes. */
	public String telegramLink(String userId) {
		TelegramChannel telegram = (TelegramChannel) channel("telegram");
		accounts.requireNotOnDeviceOnly(userId);
		String token = Crypto.randomToken();
		jdbc.sql("INSERT INTO telegram_link (token_hash, user_id, expires_at) VALUES (:t, :u, :exp)")
				.param("t", Crypto.sha256(token)).param("u", userId).param("exp", now().plus(TELEGRAM_LINK_TTL)).update();
		return telegram.startLink(token);
	}

	/** From the bot's {@code /start <token>}. The token works once. */
	boolean linkTelegram(String token, String chatId) {
		String hash = Crypto.sha256(token);
		Optional<String> user = jdbc.sql("SELECT user_id FROM telegram_link WHERE token_hash = :t AND expires_at > :now")
				.param("t", hash).param("now", now()).query(String.class).optional();
		if (user.isEmpty() || jdbc.sql("DELETE FROM telegram_link WHERE token_hash = :t").param("t", hash).update() == 0 || accounts.onDeviceOnly(user.get())) {
			return false;
		}
		save(user.get(), "telegram", chatId);
		accounts.audit(user.get(), user.get(), "You", "reminders_on", "telegram");
		return true;
	}

	/** From the bot's {@code /stop}, or Telegram saying the person blocked the bot. */
	int stopTelegram(String chatId) {
		return jdbc.sql("DELETE FROM reminder_channel WHERE channel = 'telegram' AND address_hash = :h")
				.param("h", crypto.keyedHash("telegram:" + chatId)).update();
	}

	/** The language of the account linked to this chat, for the bot's replies. */
	String langOfChat(String chatId) {
		return jdbc.sql("""
				SELECT u.lang FROM reminder_channel r JOIN app_user u ON u.id = r.user_id
				WHERE r.channel = 'telegram' AND r.address_hash = :h
				""").param("h", crypto.keyedHash("telegram:" + chatId)).query((rs, i) -> rs.getString(1)).optional().orElse("en");
	}

	// ---------- what is due ----------

	/** True when a scheme on the checklist still has a required document marked as missing or unmarked. */
	boolean hasUnfinishedChecklist(ObjectNode household) {
		ContentBundle b = bundles.get();
		JsonNode have = household.path("checklist").path("have");
		for (JsonNode slug : household.path("checklist").path("schemes")) {
			JsonNode scheme = b.scheme(slug.asString("")).orElse(null);
			if (scheme == null) {
				continue;
			}
			for (JsonNode d : scheme.path("documents")) {
				if (d.path("mandatory").asBoolean(false) && !have.path(d.path("code").asString("")).asBoolean(false)) {
					return true;
				}
			}
		}
		return false;
	}

	record Due(String slug, LocalDate closesAt) {

		String key() {
			return "deadline:" + slug + ":" + closesAt;
		}
	}

	/**
	 * Deadlines within 14 days for the schemes on this checklist, soonest first, same state rule as the
	 * app (frontend/src/lib/schedule.js). A date goes out only with a checked source behind it: if the
	 * source is missing, unverified or has changed since, the date might be wrong and we say nothing
	 * rather than send it (CLAUDE.md principles 2 and 4). The app still shows it with its warning.
	 */
	static List<Due> deadlinesDue(ObjectNode household, ContentBundle bundle, LocalDate today) {
		JsonNode state = Households.activeMember(household).path("profile").path("state");
		List<Due> due = new ArrayList<>();
		for (JsonNode slug : household.path("checklist").path("schemes")) {
			JsonNode scheme = bundle.scheme(slug.asString("")).orElse(null);
			if (scheme == null) {
				continue;
			}
			for (JsonNode d : scheme.path("deadlines")) {
				LocalDate closes = date(d.path("closes_at"));
				boolean sameState = !d.path("state_code").isString() || !state.isString() || d.path("state_code").asString().equals(state.asString());
				if (closes == null || !sameState || !hasCheckedSource(scheme, d.path("source").asString(""))) {
					continue;
				}
				long days = ChronoUnit.DAYS.between(today, closes);
				if (days >= 0 && days <= DEADLINE_WARN_DAYS) {
					due.add(new Due(slug.asString(), closes));
				}
			}
		}
		due.sort(Comparator.comparing(Due::closesAt));
		return due;
	}

	private static LocalDate date(JsonNode n) {
		if (!n.isString() || n.asString().length() < 10) {
			return null;
		}
		try {
			return LocalDate.parse(n.asString().substring(0, 10));
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	private static boolean hasCheckedSource(JsonNode scheme, String sourceId) {
		for (JsonNode s : scheme.path("sources")) {
			if (s.path("id").asString("").equals(sourceId)) {
				return s.path("verified_at").isString() && !s.path("changed_at").isString();
			}
		}
		return false;
	}

	// ---------- sending ----------

	private record Target(String userId, String channel, String addressEnc) {}

	private Map<String, List<Target>> targets() {
		List<Target> all = new ArrayList<>();
		if (channels.get("web_push").configured()) {
			all.addAll(jdbc.sql("SELECT user_id, endpoint_enc FROM push_subscription")
					.query((rs, i) -> new Target(rs.getString(1), "web_push", rs.getString(2))).list());
		}
		all.addAll(jdbc.sql("SELECT user_id, channel, address_enc FROM reminder_channel")
				.query((rs, i) -> new Target(rs.getString(1), rs.getString(2), rs.getString(3))).list().stream()
				.filter(t -> channels.get(t.channel()).configured()).toList());
		Map<String, List<Target>> byUser = new LinkedHashMap<>();
		all.forEach(t -> byUser.computeIfAbsent(t.userId(), u -> new ArrayList<>()).add(t));
		return byUser;
	}

	private boolean claim(String userId, String channel, String key) {
		return jdbc.sql("INSERT INTO reminder_sent (user_id, channel, dedupe_key, sent_at) VALUES (:u, :c, :k, :now) ON CONFLICT DO NOTHING")
				.param("u", userId).param("c", channel).param("k", key).param("now", now()).update() == 1;
	}

	private void release(String userId, String channel, String key) {
		jdbc.sql("DELETE FROM reminder_sent WHERE user_id = :u AND channel = :c AND dedupe_key = :k")
				.param("u", userId).param("c", channel).param("k", key).update();
	}

	/** Sends one message and handles the answer. Keys are the claims it covers; on failure they are given back. */
	private boolean deliver(Target t, ReminderChannel.Message message, List<String> keys) {
		ReminderChannel.Result result;
		try {
			result = channels.get(t.channel()).send(crypto.decrypt(t.addressEnc()), message);
		} catch (RuntimeException e) {
			// One bad address or a provider hiccup must not stop everyone else's reminders.
			log.warn("Reminder through {} failed: {}", t.channel(), e.getClass().getSimpleName());
			errors.report(e, "job reminders");
			result = ReminderChannel.Result.FAILED;
		}
		switch (result) {
			case SENT -> {
				String table = "web_push".equals(t.channel()) ? "push_subscription" : "reminder_channel";
				String only = "web_push".equals(t.channel()) ? "" : " AND channel = '" + t.channel() + "'";
				jdbc.sql("UPDATE " + table + " SET last_sent_at = :now WHERE user_id = :u" + only).param("now", now()).param("u", t.userId()).update();
				meters.counter("jansathi.reminders.sent", "channel", t.channel(), "kind", message.kind().name().toLowerCase(Locale.ROOT)).increment();
				return true;
			}
			case GONE -> {
				keys.forEach(k -> release(t.userId(), t.channel(), k));
				if ("web_push".equals(t.channel())) {
					unsubscribe(t.userId());
				} else {
					jdbc.sql("DELETE FROM reminder_channel WHERE user_id = :u AND channel = :c").param("u", t.userId()).param("c", t.channel()).update();
				}
				return false;
			}
			default -> {
				keys.forEach(k -> release(t.userId(), t.channel(), k));
				return false;
			}
		}
	}

	private String lang(String userId) {
		return accounts.find(userId).map(Accounts.User::lang).orElse("en");
	}

	/** One reminder per channel per week to everyone with an unfinished checklist; returns how many went out. */
	public int sendDue() {
		return sendChecklists(LocalDate.now(INDIA));
	}

	int sendChecklists(LocalDate today) {
		String week = "checklist:" + today.get(IsoFields.WEEK_BASED_YEAR) + "-" + today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
		int sent = 0;
		for (Map.Entry<String, List<Target>> e : targets().entrySet()) {
			if (!hasUnfinishedChecklist(households.get(e.getKey()))) {
				continue;
			}
			ReminderChannel.Message message = new ReminderChannel.Message(ReminderChannel.Kind.CHECKLIST, lang(e.getKey()), null);
			for (Target t : e.getValue()) {
				if (claim(t.userId(), t.channel(), week) && deliver(t, message, List.of(week))) {
					sent++;
				}
			}
		}
		return sent;
	}

	/**
	 * One message per channel for the deadlines newly within 14 days, giving the soonest date. A
	 * deadline already reminded about on a channel is not sent there again.
	 */
	public int sendDeadlines(LocalDate today) {
		ContentBundle bundle = bundles.get();
		int sent = 0;
		for (Map.Entry<String, List<Target>> e : targets().entrySet()) {
			List<Due> due = deadlinesDue(households.get(e.getKey()), bundle, today);
			if (due.isEmpty()) {
				continue;
			}
			String lang = lang(e.getKey());
			for (Target t : e.getValue()) {
				List<Due> fresh = due.stream().filter(d -> claim(t.userId(), t.channel(), d.key())).toList();
				if (!fresh.isEmpty() && deliver(t, new ReminderChannel.Message(ReminderChannel.Kind.DEADLINE, lang, fresh.getFirst().closesAt()), fresh.stream().map(Due::key).toList())) {
					sent++;
				}
			}
		}
		return sent;
	}

	/** Monday, 10:00 in India. */
	@Scheduled(cron = "0 0 10 * * MON", zone = "Asia/Kolkata")
	void weekly() {
		run("checklist", this::sendDue);
	}

	/** Every day, 10:05 in India. */
	@Scheduled(cron = "0 5 10 * * *", zone = "Asia/Kolkata")
	void daily() {
		run("deadline", () -> sendDeadlines(LocalDate.now(INDIA)));
	}

	private void run(String name, java.util.function.IntSupplier job) {
		try {
			int sent = job.getAsInt();
			if (sent > 0) {
				log.info("Reminders sent ({}): {}", name, sent);
			}
		} catch (RuntimeException e) {
			log.error("Reminder job {} failed: {}", name, e.getClass().getName());
			errors.report(e, "job reminders " + name);
		}
	}
}
