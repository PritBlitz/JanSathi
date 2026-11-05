package org.jansathi.speech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** The ML service's speech wire format, against a local stub server. */
class HttpSpeechProviderTest {

	private HttpServer server;
	private final AtomicReference<String> seen = new AtomicReference<>();
	private final AtomicReference<String> auth = new AtomicReference<>();
	private volatile int code = 200;

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/", exchange -> {
			byte[] in = exchange.getRequestBody().readAllBytes();
			seen.set(exchange.getRequestURI() + " " + new String(in, StandardCharsets.UTF_8));
			auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
			String path = exchange.getRequestURI().getPath();
			byte[] body = switch (path) {
				case "/v1/status" -> "{\"tts\":[\"hi\",\"en\"],\"stt\":[\"hi\"],\"models\":{}}".getBytes(StandardCharsets.UTF_8);
				case "/v1/stt" -> "{\"text\":\"नमस्ते\"}".getBytes(StandardCharsets.UTF_8);
				default -> new byte[] {'R', 'I', 'F', 'F'};
			};
			exchange.getResponseHeaders().add("Content-Type", path.equals("/v1/tts") ? "audio/wav" : "application/json");
			exchange.sendResponseHeaders(code, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	private HttpSpeechProvider provider() {
		String url = "http://127.0.0.1:" + server.getAddress().getPort();
		return new HttpSpeechProvider(new SpeechProperties("ml-service", url, "tok", Duration.ofSeconds(2), null, null, null, null), RestClient.builder());
	}

	@Test
	void readsTheLanguages() {
		var languages = provider().languages();
		assertThat(languages.tts()).containsExactly("hi", "en");
		assertThat(languages.stt()).containsExactly("hi");
		assertThat(auth.get()).isEqualTo("Bearer tok");
	}

	@Test
	void aDownServiceMeansNoLanguagesNotAnError() {
		HttpSpeechProvider provider = provider();
		server.stop(0);
		assertThat(provider.languages()).isEqualTo(SpeechProvider.Languages.NONE);
	}

	@Test
	void sendsTextAndGetsWav() {
		assertThat(provider().synthesize("नमस्ते", "hi")).startsWith('R', 'I', 'F', 'F');
		assertThat(seen.get()).startsWith("/v1/tts ").contains("\"lang\":\"hi\"").contains("नमस्ते");
	}

	@Test
	void sendsAudioAndGetsText() {
		assertThat(provider().transcribe(new byte[] {65, 66}, "hi")).isEqualTo("नमस्ते");
		assertThat(seen.get()).isEqualTo("/v1/stt?lang=hi AB");
	}

	@Test
	void aRefusalAndAnOutageAreDifferentFailures() {
		code = 422;
		assertThatThrownBy(() -> provider().transcribe(new byte[] {1}, "hi")).isInstanceOf(SpeechProvider.SpeechRejectedException.class);
		code = 503;
		assertThatThrownBy(() -> provider().synthesize("a", "hi")).isInstanceOf(SpeechProvider.SpeechUnavailableException.class);
	}
}
