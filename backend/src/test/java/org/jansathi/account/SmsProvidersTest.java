package org.jansathi.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.jansathi.JansathiProperties;
import org.jansathi.web.ProviderUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The SMS senders against a local stand-in for each provider, and the production guard. No real provider is called. */
class SmsProvidersTest {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private record Seen(String path, String authkey, String authorization, String body) {}

	private final List<Seen> seen = new ArrayList<>();
	private int status = 200;
	private HttpServer server;

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", e -> {
			seen.add(new Seen(e.getRequestURI().getPath(), e.getRequestHeaders().getFirst("authkey"), e.getRequestHeaders().getFirst("Authorization"),
					new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
			e.sendResponseHeaders(status, -1);
			e.close();
		});
		server.start();
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	private String base() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	private SmsProperties msg91(String checklist, String deadline) {
		return new SmsProperties("msg91", "OTP-TPL", checklist, deadline, new SmsProperties.Msg91("KEY", base()), null);
	}

	private SmsProperties twilio() {
		return new SmsProperties("twilio", null, "c", "d", null, new SmsProperties.Twilio("AC1", "secret", "MG1", base()));
	}

	@Test
	void msg91SendsTheTemplateWithTheCodeAsAVariable() {
		assertThat(new Msg91SmsSender(msg91(null, null), MAPPER).sendCode("9876543210", "123456", "hi")).isNull();

		Seen s = seen.getFirst();
		assertThat(s.path()).isEqualTo("/flow");
		assertThat(s.authkey()).isEqualTo("KEY");
		JsonNode body = MAPPER.readTree(s.body());
		assertThat(body.path("template_id").asString()).isEqualTo("OTP-TPL");
		assertThat(body.path("recipients").get(0).path("mobiles").asString()).isEqualTo("919876543210");
		assertThat(body.path("recipients").get(0).path("code").asString()).isEqualTo("123456");
	}

	@Test
	void msg91RemindersNeedBothTemplates() {
		assertThat(new Msg91SmsSender(msg91(null, "d"), MAPPER).sendsReminders()).isFalse();
		Msg91SmsSender sender = new Msg91SmsSender(msg91("CHK", "DDL"), MAPPER);
		assertThat(sender.sendsReminders()).isTrue();
		assertThat(sender.sendReminder("9876543210", SmsSender.Reminder.DEADLINE, LocalDate.of(2026, 12, 1))).isTrue();
		JsonNode body = MAPPER.readTree(seen.getFirst().body());
		assertThat(body.path("template_id").asString()).isEqualTo("DDL");
		assertThat(body.path("recipients").get(0).path("date").asString()).isEqualTo("1 Dec 2026");
	}

	@Test
	void twilioPostsTheRegisteredWordsThroughTheMessagingService() {
		assertThat(new TwilioSmsSender(twilio()).sendCode("9876543210", "123456", "en")).isNull();

		Seen s = seen.getFirst();
		assertThat(s.path()).isEqualTo("/Accounts/AC1/Messages.json");
		assertThat(s.authorization()).isEqualTo("Basic " + Base64.getEncoder().encodeToString("AC1:secret".getBytes(StandardCharsets.UTF_8)));
		String form = URLDecoder.decode(s.body(), StandardCharsets.UTF_8);
		assertThat(form).contains("To=+919876543210").contains("MessagingServiceSid=MG1").contains("Body=" + SmsText.code("123456"));
	}

	@Test
	void aRefusedCodeIsAnErrorNotASilentSuccess() {
		status = 401;
		assertThatThrownBy(() -> new Msg91SmsSender(msg91(null, null), MAPPER).sendCode("9876543210", "123456", "en")).isInstanceOf(ProviderUnavailableException.class);
		assertThatThrownBy(() -> new TwilioSmsSender(twilio()).sendCode("9876543210", "123456", "en")).isInstanceOf(ProviderUnavailableException.class);
		assertThat(new TwilioSmsSender(twilio()).sendReminder("9876543210", SmsSender.Reminder.CHECKLIST, null)).isFalse();
	}

	@Test
	void noWordsOfAnSmsPromiseOrNameAScheme() {
		for (String text : List.of(SmsText.code("123456"), SmsText.reminder(SmsSender.Reminder.CHECKLIST, null), SmsText.reminder(SmsSender.Reminder.DEADLINE, LocalDate.of(2026, 12, 1)))) {
			assertThat(text.toLowerCase()).doesNotContain("eligible").doesNotContain("will get").doesNotContain("guarantee");
		}
	}

	@Test
	void missingSettingsFailAtStartNotAtTheFirstSms() {
		assertThatThrownBy(() -> new Msg91SmsSender(new SmsProperties("msg91", null, null, null, null, null), MAPPER)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new TwilioSmsSender(new SmsProperties("twilio", null, null, null, null, null))).isInstanceOf(IllegalStateException.class);
	}

	@Configuration
	@EnableConfigurationProperties(JansathiProperties.class)
	@Import(ProductionGuard.class)
	static class GuardOnly {}

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(GuardOnly.class)
			// build.gradle.kts turns drafts on for every test; the guard refuses them.
			.withSystemProperties("jansathi.include-drafts=false");
	private static final String LONG_SECRET = "jansathi.secret=" + "x".repeat(40);

	@Test
	void theDevSenderStopsAProductionStart() {
		runner.withPropertyValues("jansathi.production=true", LONG_SECRET, "jansathi.sms.provider=dev").run(c -> {
			assertThat(c).hasFailed();
			assertThat(c.getStartupFailure()).rootCause().hasMessageContaining("JANSATHI_SMS_PROVIDER is dev");
		});
		runner.withPropertyValues("spring.profiles.active=production", LONG_SECRET, "jansathi.sms.provider=dev").run(c -> assertThat(c).hasFailed());
	}

	@Test
	void aShortSecretStopsAProductionStart() {
		runner.withPropertyValues("jansathi.production=true", "jansathi.secret=short", "jansathi.sms.provider=msg91").run(c -> {
			assertThat(c).hasFailed();
			assertThat(c.getStartupFailure()).rootCause().hasMessageContaining("JANSATHI_SECRET");
		});
	}

	@Test
	void aProperProductionSetupStartsAndDevelopmentIsLeftAlone() {
		runner.withPropertyValues("jansathi.production=true", LONG_SECRET, "jansathi.sms.provider=twilio").run(c -> assertThat(c).hasNotFailed());
		runner.withPropertyValues("jansathi.sms.provider=dev").run(c -> assertThat(c).hasNotFailed());
	}
}
