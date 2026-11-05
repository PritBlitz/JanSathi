package org.jansathi.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jansathi.account.SmsSender;
import org.jansathi.content.ContentBundle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Telegram, SMS and WhatsApp reminders, against a local stand-in for each provider. No real provider
 * is called. The deadline below is a test fixture for the job's logic, not a scheme fact: the bundle
 * is a temporary copy.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReminderChannelsTest {

	private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
	private static final JsonMapper MAPPER = JsonMapper.builder().build();
	private static final String SECRET = "webhook-secret";
	private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);
	private static final List<String> TELEGRAM = new CopyOnWriteArrayList<>();
	private static final List<String> WHATSAPP = new CopyOnWriteArrayList<>();
	private static volatile int telegramStatus = 200;
	private static final HttpServer SERVER = server();

	private static HttpServer server() {
		try {
			HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			s.createContext("/botTOKEN/sendMessage", e -> {
				TELEGRAM.add(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
				e.sendResponseHeaders(telegramStatus, -1);
				e.close();
			});
			s.createContext("/v23.0/PHONEID/messages", e -> {
				WHATSAPP.add(e.getRequestHeaders().getFirst("Authorization") + " " + new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
				e.sendResponseHeaders(200, -1);
				e.close();
			});
			s.start();
			return s;
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	static class FakeSms implements SmsSender {
		final List<String> sent = new CopyOnWriteArrayList<>();

		@Override
		public String sendCode(String phone, String code, String lang) {
			return code;
		}

		@Override
		public boolean sendsReminders() {
			return true;
		}

		@Override
		public boolean sendReminder(String phone, Reminder reminder, LocalDate closesAt) {
			sent.add(phone + " " + reminder + " " + closesAt);
			return true;
		}
	}

	@TestConfiguration
	static class Fakes {
		@Bean
		@Primary
		FakeSms fakeSms() {
			return new FakeSms();
		}
	}

	/** A copy of the draft bundle with two dated deadlines on ignoaps: one soon, one further off. */
	private static Path bundleWithDeadlines() throws IOException {
		Path dir = Files.createTempDirectory("jansathi-bundle");
		Path repo = Path.of(System.getProperty("jansathi.repoRoot"));
		ObjectNode bundle = (ObjectNode) MAPPER.readTree(Files.readString(repo.resolve("frontend/public/data/bundle.preview.json")));
		for (JsonNode s : bundle.path("schemes")) {
			if (s.path("slug").asString().equals("ignoaps")) {
				String source = null;
				for (JsonNode src : s.path("sources")) {
					if (src.path("verified_at").isString() && !src.path("changed_at").isString()) {
						source = src.path("id").asString();
					}
				}
				ArrayNode deadlines = ((ObjectNode) s).putArray("deadlines");
				deadlines.addObject().put("closes_at", TODAY.plusDays(10).toString()).put("source", source);
				deadlines.addObject().put("closes_at", TODAY.plusDays(30).toString()).put("source", source);
			}
		}
		Files.writeString(dir.resolve("bundle.preview.json"), MAPPER.writeValueAsString(bundle));
		Files.writeString(dir.resolve("bundle.json"), Files.readString(repo.resolve("frontend/public/data/bundle.json")));
		Path states = repo.resolve("frontend/public/data/states");
		if (Files.isDirectory(states)) {
			Files.createDirectories(dir.resolve("states"));
			try (var files = Files.list(states)) {
				for (Path f : files.toList()) {
					Files.copy(f, dir.resolve("states").resolve(f.getFileName()));
				}
			}
		}
		return dir;
	}

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) throws IOException {
		String base = "http://127.0.0.1:" + SERVER.getAddress().getPort();
		registry.add("spring.datasource.url", () -> "jdbc:h2:mem:channels;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
		registry.add("jansathi.secret", () -> "test-secret-not-for-production");
		registry.add("jansathi.reminders.telegram.bot-token", () -> "TOKEN");
		registry.add("jansathi.reminders.telegram.bot-username", () -> "JanSathiBot");
		registry.add("jansathi.reminders.telegram.webhook-secret", () -> SECRET);
		registry.add("jansathi.reminders.telegram.base-url", () -> base);
		registry.add("jansathi.reminders.whatsapp.access-token", () -> "WA-TOKEN");
		registry.add("jansathi.reminders.whatsapp.phone-number-id", () -> "PHONEID");
		registry.add("jansathi.reminders.whatsapp.checklist-template", () -> "checklist_reminder");
		registry.add("jansathi.reminders.whatsapp.deadline-template", () -> "deadline_reminder");
		registry.add("jansathi.reminders.whatsapp.languages", () -> "en,hi");
		registry.add("jansathi.reminders.whatsapp.base-url", () -> base);
		String dir = bundleWithDeadlines().toString();
		registry.add("jansathi.bundle-dir", () -> dir);
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	Reminders reminders;

	@Autowired
	FakeSms sms;

	@BeforeEach
	void reset() {
		TELEGRAM.clear();
		WHATSAPP.clear();
		sms.sent.clear();
		telegramStatus = 200;
		jdbc.sql("DELETE FROM reminder_channel").update();
		jdbc.sql("DELETE FROM reminder_sent").update();
	}

	private JsonNode body(org.springframework.test.web.servlet.ResultActions r) throws Exception {
		String s = r.andReturn().getResponse().getContentAsString();
		return MAPPER.readTree(s.isEmpty() ? "{}" : s);
	}

	private String signIn(String phone) throws Exception {
		String code = body(mvc.perform(post("/api/v1/auth/code").contentType(JSON).content("{\"phone\": \"" + phone + "\"}")
				.with(r -> { r.setRemoteAddr("10.0.2." + phone.substring(8)); return r; }))).path("dev_code").asString();
		return "Bearer " + body(mvc.perform(post("/api/v1/auth/verify").contentType(JSON)
				.content("{\"phone\": \"" + phone + "\", \"code\": \"" + code + "\"}"))).path("token").asString();
	}

	private void checklist(String auth) throws Exception {
		mvc.perform(put("/api/v1/me/household").header("Authorization", auth).contentType(JSON)
						.content("{\"members\": [{\"id\": \"me\", \"profile\": {}}], \"checklist\": {\"schemes\": [\"ignoaps\"], \"have\": {}}}"))
				.andExpect(status().isOk());
	}

	private void webhook(String json) throws Exception {
		mvc.perform(post("/api/v1/reminders/telegram/webhook").header("X-Telegram-Bot-Api-Secret-Token", SECRET).contentType(JSON).content(json))
				.andExpect(status().isOk());
	}

	private static String chatMessage(long chat, String text) {
		return "{\"message\": {\"chat\": {\"id\": " + chat + ", \"type\": \"private\"}, \"text\": \"" + text + "\"}}";
	}

	private String linkTelegram(String auth, long chat) throws Exception {
		String link = body(mvc.perform(post("/api/v1/me/reminders/telegram").header("Authorization", auth)).andExpect(status().isOk())).path("link").asString();
		assertThat(link).startsWith("https://t.me/JanSathiBot?start=");
		String token = link.substring(link.indexOf('=') + 1);
		assertThat(token).matches("[A-Za-z0-9_-]{1,64}");
		webhook(chatMessage(chat, "/start " + token));
		return token;
	}

	@Test
	void onlyConfiguredChannelsAreOffered() throws Exception {
		// No VAPID keys in this test, so Web Push is not offered.
		mvc.perform(get("/api/v1/reminders/channels")).andExpect(status().isOk())
				.andExpect(jsonPath("$.channels.length()").value(3))
				.andExpect(jsonPath("$.channels[0]").value("telegram"))
				.andExpect(jsonPath("$.channels[1]").value("sms"))
				.andExpect(jsonPath("$.channels[2]").value("whatsapp"));
		String me = signIn("9876520001");
		mvc.perform(put("/api/v1/me/reminders/web_push").header("Authorization", me).contentType(JSON).content("{}")).andExpect(status().isServiceUnavailable());
		mvc.perform(put("/api/v1/me/reminders/pigeon").header("Authorization", me).contentType(JSON).content("{}")).andExpect(status().isNotFound());
	}

	@Test
	void telegramLinksByDeepLinkAndTheChatIdIsEncrypted() throws Exception {
		String me = signIn("9876520002");
		mvc.perform(put("/api/v1/me/reminders/telegram").header("Authorization", me)).andExpect(status().isConflict());
		String token = linkTelegram(me, 555001);

		assertThat(TELEGRAM).hasSize(1);
		assertThat(TELEGRAM.getFirst()).contains("555001").contains("reminders are on");
		String stored = jdbc.sql("SELECT address_enc FROM reminder_channel WHERE channel = 'telegram'").query(String.class).single();
		assertThat(stored).doesNotContain("555001");
		mvc.perform(get("/api/v1/me/reminders").header("Authorization", me)).andExpect(status().isOk()).andExpect(jsonPath("$.channels[0].channel").value("telegram"));
		mvc.perform(put("/api/v1/me/reminders/telegram").header("Authorization", me)).andExpect(status().isOk());

		// The token works once.
		String stranger = signIn("9876520003");
		webhook(chatMessage(666001, "/start " + token));
		assertThat(jdbc.sql("SELECT COUNT(*) FROM reminder_channel").query(Integer.class).single()).isOne();
		assertThat(TELEGRAM.getLast()).contains("666001").contains("expired");
		assertThat(stranger).isNotBlank();
	}

	@Test
	void theWebhookNeedsTheSecretAndIgnoresGroups() throws Exception {
		String update = chatMessage(1, "/stop");
		mvc.perform(post("/api/v1/reminders/telegram/webhook").contentType(JSON).content(update)).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v1/reminders/telegram/webhook").header("X-Telegram-Bot-Api-Secret-Token", "wrong").contentType(JSON).content(update))
				.andExpect(status().isUnauthorized());

		String me = signIn("9876520004");
		String link = body(mvc.perform(post("/api/v1/me/reminders/telegram").header("Authorization", me))).path("link").asString();
		webhook("{\"message\": {\"chat\": {\"id\": -100, \"type\": \"group\"}, \"text\": \"/start " + link.substring(link.indexOf('=') + 1) + "\"}}");
		assertThat(jdbc.sql("SELECT COUNT(*) FROM reminder_channel").query(Integer.class).single()).isZero();
	}

	@Test
	void stopAndBlockingTheBotAndDeletingTheAccountRemoveTheChat() throws Exception {
		String me = signIn("9876520005");
		linkTelegram(me, 555002);
		webhook(chatMessage(555002, "/stop"));
		assertThat(jdbc.sql("SELECT COUNT(*) FROM reminder_channel").query(Integer.class).single()).isZero();
		assertThat(TELEGRAM.getLast()).contains("off");

		linkTelegram(me, 555002);
		webhook("{\"my_chat_member\": {\"chat\": {\"id\": 555002}, \"new_chat_member\": {\"status\": \"kicked\"}}}");
		assertThat(jdbc.sql("SELECT COUNT(*) FROM reminder_channel").query(Integer.class).single()).isZero();

		linkTelegram(me, 555002);
		mvc.perform(delete("/api/v1/me").header("Authorization", me)).andExpect(status().isOk());
		assertThat(jdbc.sql("SELECT COUNT(*) FROM reminder_channel").query(Integer.class).single()).isZero();
	}

	@Test
	void smsAndWhatsAppGoOnlyToThePersonsOwnNumber() throws Exception {
		String me = signIn("9876520006");
		mvc.perform(put("/api/v1/me/reminders/sms").header("Authorization", me).contentType(JSON).content("{\"phone\": \"9876529999\"}")).andExpect(status().isBadRequest());
		mvc.perform(put("/api/v1/me/reminders/sms").header("Authorization", me).contentType(JSON).content("{}")).andExpect(status().isBadRequest());
		mvc.perform(put("/api/v1/me/reminders/sms").header("Authorization", me).contentType(JSON).content("{\"phone\": \"9876520006\"}")).andExpect(status().isOk());
		mvc.perform(put("/api/v1/me/reminders/whatsapp").header("Authorization", me).contentType(JSON).content("{\"phone\": \"9876520006\"}")).andExpect(status().isOk());
		assertThat(jdbc.sql("SELECT address_enc FROM reminder_channel").query(String.class).list()).noneMatch(a -> a.contains("9876520006"));

		checklist(me);
		assertThat(reminders.sendChecklists(TODAY)).isEqualTo(2);
		assertThat(sms.sent).containsExactly("9876520006 CHECKLIST null");
		assertThat(WHATSAPP).hasSize(1);
		assertThat(WHATSAPP.getFirst()).startsWith("Bearer WA-TOKEN ").contains("\"to\":\"919876520006\"").contains("\"type\":\"template\"").contains("checklist_reminder")
				.contains("\"code\":\"en\"");

		// Once a week per channel.
		assertThat(reminders.sendChecklists(TODAY.plusDays(1))).isZero();
		assertThat(reminders.sendChecklists(TODAY.plusDays(7))).isEqualTo(2);

		mvc.perform(delete("/api/v1/me/reminders/sms").header("Authorization", me)).andExpect(status().isOk());
		mvc.perform(get("/api/v1/me/reminders").header("Authorization", me)).andExpect(jsonPath("$.channels.length()").value(1));
	}

	@Test
	void aDeadlineRemindsOnceFourteenDaysAheadAndNamesNoScheme() throws Exception {
		String me = signIn("9876520007");
		linkTelegram(me, 555003);
		mvc.perform(put("/api/v1/me/reminders/whatsapp").header("Authorization", me).contentType(JSON).content("{\"phone\": \"9876520007\"}")).andExpect(status().isOk());
		checklist(me);
		TELEGRAM.clear();

		// Further off than 14 days from today, the nearer one is still out of range.
		assertThat(reminders.sendDeadlines(TODAY.minusDays(5))).isZero();
		assertThat(reminders.sendDeadlines(TODAY)).isEqualTo(2);
		assertThat(TELEGRAM).hasSize(1);
		assertThat(TELEGRAM.getFirst()).contains("15").contains("official source").doesNotContain("ignoaps").doesNotContain("pension");
		assertThat(WHATSAPP.getFirst()).contains("deadline_reminder").contains("15 Nov 2026");

		// Deduplicated: the job runs daily.
		assertThat(reminders.sendDeadlines(TODAY.plusDays(1))).isZero();
		// The later deadline comes into range on its own day.
		assertThat(reminders.sendDeadlines(TODAY.plusDays(16))).isEqualTo(2);
		// Past the close, nothing.
		assertThat(reminders.sendDeadlines(TODAY.plusDays(31))).isZero();
	}

	@Test
	void aFailedSendIsTriedAgainAndABlockedBotIsForgotten() throws Exception {
		String me = signIn("9876520008");
		linkTelegram(me, 555004);
		checklist(me);

		telegramStatus = 500;
		assertThat(reminders.sendDeadlines(TODAY)).isZero();
		telegramStatus = 200;
		assertThat(reminders.sendDeadlines(TODAY)).isEqualTo(1);

		telegramStatus = 403;
		assertThat(reminders.sendChecklists(TODAY)).isZero();
		assertThat(jdbc.sql("SELECT COUNT(*) FROM reminder_channel").query(Integer.class).single()).isZero();
	}

	@Test
	void aDateWithoutACheckedSourceIsNotSent() throws Exception {
		ObjectNode household = (ObjectNode) MAPPER.readTree("{\"members\": [{\"id\": \"me\", \"profile\": {\"state\": \"UP\"}}], \"active\": \"me\", \"checklist\": {\"schemes\": [\"s\"]}}");
		String closes = TODAY.plusDays(3).toString();
		ObjectNode scheme = (ObjectNode) MAPPER.readTree("""
				{"slug": "s", "sources": [{"id": "ok", "verified_at": "2026-10-25"}, {"id": "draft", "verified_at": null}, {"id": "moved", "verified_at": "2026-10-25", "changed_at": "2026-11-01"}],
				"deadlines": [{"closes_at": "%s", "source": "ok", "state_code": "BR"}, {"closes_at": "%s", "source": "draft"}, {"closes_at": "%s", "source": "moved"}, {"closes_at": "%s", "source": "none"}, {"closes_at": null, "source": "ok"}]}
				""".formatted(closes, closes, closes, closes));
		ContentBundle bundle = new ContentBundle("v", false, MAPPER.createObjectNode(), MAPPER.createObjectNode(), null, MAPPER.createObjectNode(), List.of(scheme), java.util.Map.of());
		assertThat(Reminders.deadlinesDue(household, bundle, TODAY)).isEmpty();

		((ObjectNode) scheme.path("deadlines").get(0)).remove("state_code");
		assertThat(Reminders.deadlinesDue(household, bundle, TODAY)).containsExactly(new Reminders.Due("s", TODAY.plusDays(3)));
	}
}
