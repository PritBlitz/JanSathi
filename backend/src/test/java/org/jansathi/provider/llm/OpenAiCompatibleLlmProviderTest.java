package org.jansathi.provider.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.jansathi.JansathiProperties;
import org.jansathi.provider.llm.LlmProvider.CompletionOptions;
import org.jansathi.provider.llm.LlmProvider.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** The chat-completions wire format, against a local stub server (no network, no API key). */
class OpenAiCompatibleLlmProviderTest {

	private HttpServer server;
	private final AtomicReference<String> request = new AtomicReference<>();
	private final AtomicReference<String> auth = new AtomicReference<>();
	private volatile int status = 200;

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/chat/completions", exchange -> {
			request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
			byte[] body = "{\"model\":\"stub-1\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"status\\\":\\\"not_found\\\"}\"}}]}"
					.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	private OpenAiCompatibleLlmProvider provider() {
		String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
		return new OpenAiCompatibleLlmProvider(
				new JansathiProperties.Llm("openai-compatible", base, "secret", "stub-model", Duration.ofSeconds(5)), RestClient.builder());
	}

	@Test
	void sendsSystemUserJsonModeAndKey() {
		var c = provider().complete("SYSTEM", List.of(Message.user("QUESTION")), new CompletionOptions(100, 0.1, true));

		assertThat(c.text()).isEqualTo("{\"status\":\"not_found\"}");
		assertThat(c.model()).isEqualTo("stub-1");
		assertThat(auth.get()).isEqualTo("Bearer secret");
		var sent = JsonMapper.builder().build().readTree(request.get());
		assertThat(sent.path("model").asString()).isEqualTo("stub-model");
		assertThat(sent.path("messages").path(0).path("role").asString()).isEqualTo("system");
		assertThat(sent.path("messages").path(0).path("content").asString()).isEqualTo("SYSTEM");
		assertThat(sent.path("messages").path(1).path("content").asString()).isEqualTo("QUESTION");
		assertThat(sent.path("response_format").path("type").asString()).isEqualTo("json_object");
	}

	@Test
	void serverErrorBecomesLlmException() {
		status = 500;

		assertThatThrownBy(() -> provider().complete("S", List.of(Message.user("Q")), new CompletionOptions(10, 0, false)))
				.isInstanceOf(LlmProvider.LlmException.class);
	}
}
