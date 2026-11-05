package org.jansathi.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
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

@SpringBootTest
@AutoConfigureMockMvc
class RemindersTest {

	private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
	private static final JsonMapper MAPPER = JsonMapper.builder().build();
	private static final String ENDPOINT = "https://fcm.googleapis.com/fcm/send/abc123";

	static class FakeSender implements PushSender {
		final List<String> sent = new ArrayList<>();
		Result answer = Result.SENT;

		@Override
		public String publicKey() {
			return "test-public-key";
		}

		@Override
		public Result send(String endpoint) {
			sent.add(endpoint);
			return answer;
		}
	}

	@TestConfiguration
	static class Fake {
		@Bean
		@Primary
		FakeSender fakeSender() {
			return new FakeSender();
		}
	}

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> "jdbc:h2:mem:reminders;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
		registry.add("jansathi.secret", () -> "test-secret-not-for-production");
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	FakeSender sender;

	@Autowired
	Reminders reminders;

	@BeforeEach
	void reset() {
		sender.sent.clear();
		sender.answer = PushSender.Result.SENT;
		jdbc.sql("DELETE FROM push_subscription").update();
	}

	private JsonNode body(org.springframework.test.web.servlet.ResultActions r) throws Exception {
		return MAPPER.readTree(r.andReturn().getResponse().getContentAsString());
	}

	private String signIn(String phone) throws Exception {
		String address = "10.0.1." + phone.substring(8);
		String code = body(mvc.perform(post("/api/v1/auth/code").contentType(JSON).content("{\"phone\": \"" + phone + "\"}")
				.with(r -> { r.setRemoteAddr(address); return r; }))).path("dev_code").asString();
		return "Bearer " + body(mvc.perform(post("/api/v1/auth/verify").contentType(JSON)
				.content("{\"phone\": \"" + phone + "\", \"code\": \"" + code + "\"}"))).path("token").asString();
	}

	private void household(String auth, String have) throws Exception {
		mvc.perform(put("/api/v1/me/household").header("Authorization", auth).contentType(JSON)
						.content("{\"members\": [{\"id\": \"me\", \"profile\": {}}], \"checklist\": {\"schemes\": [\"ignoaps\"], \"have\": " + have + "}}"))
				.andExpect(status().isOk());
	}

	private void subscribe(String auth) throws Exception {
		mvc.perform(put("/api/v1/me/reminders/push").header("Authorization", auth).contentType(JSON)
						.content("{\"endpoint\": \"" + ENDPOINT + "\", \"p256dh\": \"k\", \"auth\": \"a\"}"))
				.andExpect(status().isOk());
	}

	@Test
	void keyIsPublicButSubscribingNeedsSignIn() throws Exception {
		mvc.perform(get("/api/v1/reminders/push-key")).andExpect(status().isOk()).andExpect(jsonPath("$.public_key").value("test-public-key"));
		mvc.perform(put("/api/v1/me/reminders/push").contentType(JSON).content("{\"endpoint\": \"" + ENDPOINT + "\"}")).andExpect(status().isUnauthorized());
		mvc.perform(delete("/api/v1/me/reminders/push")).andExpect(status().isUnauthorized());
	}

	@Test
	void onlyBrowserPushServicesAreAccepted() throws Exception {
		String me = signIn("9876510001");
		for (String bad : List.of("http://fcm.googleapis.com/x", "https://localhost/x", "https://169.254.169.254/latest", "https://fcm.googleapis.com.evil.example/x",
				"https://user@fcm.googleapis.com/x", "https://fcm.googleapis.com:8443/x", "not a url")) {
			mvc.perform(put("/api/v1/me/reminders/push").header("Authorization", me).contentType(JSON).content("{\"endpoint\": \"" + bad + "\"}"))
					.andExpect(status().isBadRequest());
		}
		assertThat(Reminders.isPushService("https://updates.push.services.mozilla.com/wpush/v2/x")).isTrue();
		assertThat(Reminders.isPushService("https://web.push.apple.com/x")).isTrue();
		assertThat(Reminders.isPushService("https://wns2-pn1p.notify.windows.com/w/?token=x")).isTrue();
	}

	@Test
	void endpointIsEncryptedAndTheKeysAreNotKept() throws Exception {
		subscribe(signIn("9876510002"));
		String stored = jdbc.sql("SELECT endpoint_enc FROM push_subscription").query(String.class).single();
		assertThat(stored).doesNotContain("fcm.googleapis.com");
	}

	@Test
	void remindsOnlyWhileARequiredDocumentIsMissing() throws Exception {
		String me = signIn("9876510003");
		subscribe(me);
		household(me, "{}");
		assertThat(reminders.sendDue()).isEqualTo(1);
		assertThat(sender.sent).containsExactly(ENDPOINT);

		StringBuilder all = new StringBuilder("{");
		JsonNode scheme = body(mvc.perform(get("/api/v1/schemes/ignoaps")));
		for (JsonNode d : scheme.path("scheme").path("documents")) {
			all.append(all.length() > 1 ? "," : "").append('"').append(d.path("code").asString()).append("\": true");
		}
		household(me, all.append('}').toString());
		sender.sent.clear();
		assertThat(reminders.sendDue()).isZero();
		assertThat(sender.sent).isEmpty();
	}

	@Test
	void aSubscriptionThePushServiceDroppedIsForgotten() throws Exception {
		String me = signIn("9876510004");
		subscribe(me);
		household(me, "{}");

		sender.answer = PushSender.Result.FAILED;
		reminders.sendDue();
		assertThat(jdbc.sql("SELECT COUNT(*) FROM push_subscription").query(Integer.class).single()).isOne();

		sender.answer = PushSender.Result.GONE;
		reminders.sendDue();
		assertThat(jdbc.sql("SELECT COUNT(*) FROM push_subscription").query(Integer.class).single()).isZero();
	}

	@Test
	void turningOffAndDeletingTheAccountRemoveTheSubscription() throws Exception {
		String me = signIn("9876510005");
		subscribe(me);
		mvc.perform(delete("/api/v1/me/reminders/push").header("Authorization", me)).andExpect(status().isOk());
		assertThat(jdbc.sql("SELECT COUNT(*) FROM push_subscription").query(Integer.class).single()).isZero();

		subscribe(me);
		mvc.perform(delete("/api/v1/me").header("Authorization", me)).andExpect(status().isOk());
		assertThat(jdbc.sql("SELECT COUNT(*) FROM push_subscription").query(Integer.class).single()).isZero();
	}

	// ---------- the real sender, against a local stand-in for a push service ----------

	private static String b64(byte[] b) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
	}

	private static byte[] fixed32(java.math.BigInteger n) {
		byte[] raw = n.toByteArray();
		byte[] out = new byte[32];
		int len = Math.min(raw.length, 32);
		System.arraycopy(raw, raw.length - len, out, 32 - len, len);
		return out;
	}

	private static KeyPair keys() throws Exception {
		KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
		g.initialize(new ECGenParameterSpec("secp256r1"));
		return g.generateKeyPair();
	}

	private static VapidPushSender vapid(KeyPair k) {
		ECPublicKey pub = (ECPublicKey) k.getPublic();
		byte[] point = new byte[65];
		point[0] = 4;
		System.arraycopy(fixed32(pub.getW().getAffineX()), 0, point, 1, 32);
		System.arraycopy(fixed32(pub.getW().getAffineY()), 0, point, 33, 32);
		return new VapidPushSender(b64(point), b64(fixed32(((ECPrivateKey) k.getPrivate()).getS())), "mailto:test@example.org");
	}

	@Test
	void vapidTokenIsAValidEs256SignatureForThePushServicesOrigin() throws Exception {
		KeyPair k = keys();
		VapidPushSender s = vapid(k);
		String header = s.authorization("https://fcm.googleapis.com/fcm/send/abc", Instant.ofEpochSecond(1000));

		assertThat(header).startsWith("vapid t=").endsWith(", k=" + s.publicKey());
		String token = header.substring("vapid t=".length(), header.indexOf(','));
		String[] parts = token.split("\\.");
		JsonNode claims = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
		assertThat(claims.path("aud").asString()).isEqualTo("https://fcm.googleapis.com");
		assertThat(claims.path("exp").asLong()).isEqualTo(1000 + 12 * 3600);
		assertThat(claims.path("sub").asString()).isEqualTo("mailto:test@example.org");

		Signature verify = Signature.getInstance("SHA256withECDSAinP1363Format");
		verify.initVerify(k.getPublic());
		verify.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
		assertThat(verify.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
	}

	@Test
	void sendPostsAnEmptyBodyAndReadsThePushServicesAnswer() throws Exception {
		AtomicReference<Integer> status = new AtomicReference<>(201);
		AtomicReference<byte[]> received = new AtomicReference<>();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", e -> {
			received.set(e.getRequestBody().readAllBytes());
			e.sendResponseHeaders(status.get(), -1);
			e.close();
		});
		server.start();
		try {
			VapidPushSender s = vapid(keys());
			String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/push/abc";
			assertThat(s.send(url)).isEqualTo(PushSender.Result.SENT);
			assertThat(Arrays.equals(received.get(), new byte[0])).isTrue();
			status.set(410);
			assertThat(s.send(url)).isEqualTo(PushSender.Result.GONE);
			status.set(404);
			assertThat(s.send(url)).isEqualTo(PushSender.Result.GONE);
			status.set(503);
			assertThat(s.send(url)).isEqualTo(PushSender.Result.FAILED);
		} finally {
			server.stop(0);
		}
	}
}
