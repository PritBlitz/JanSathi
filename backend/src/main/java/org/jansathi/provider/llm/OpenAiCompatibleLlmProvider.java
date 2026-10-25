package org.jansathi.provider.llm;

import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jansathi.JansathiProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * Any provider with the OpenAI chat-completions API: Groq, a local Ollama ({@code /v1}), Gemini's
 * OpenAI-compatible endpoint, and others. Plain HTTP, no SDK, so swapping providers is config only.
 */
public class OpenAiCompatibleLlmProvider implements LlmProvider {

	private final RestClient http;
	private final String model;

	public OpenAiCompatibleLlmProvider(JansathiProperties.Llm config, RestClient.Builder builder) {
		if (config.baseUrl() == null || config.baseUrl().isBlank() || config.model() == null || config.model().isBlank()) {
			throw new IllegalStateException("jansathi.llm.base-url and jansathi.llm.model are required for the openai-compatible provider");
		}
		var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(config.timeout()).build());
		factory.setReadTimeout(config.timeout());
		RestClient.Builder b = builder.clone().baseUrl(config.baseUrl()).requestFactory(factory);
		if (config.apiKey() != null && !config.apiKey().isBlank()) {
			b.defaultHeader("Authorization", "Bearer " + config.apiKey());
		}
		this.http = b.build();
		this.model = config.model();
	}

	@Override
	public boolean enabled() {
		return true;
	}

	@Override
	public String modelId() {
		return model;
	}

	@Override
	public Completion complete(String system, List<Message> messages, CompletionOptions options) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", model);
		List<Map<String, String>> all = new java.util.ArrayList<>();
		all.add(Map.of("role", "system", "content", system));
		messages.forEach(m -> all.add(Map.of("role", m.role(), "content", m.content())));
		body.put("messages", all);
		body.put("temperature", options.temperature());
		body.put("max_tokens", options.maxTokens());
		if (options.jsonOutput()) {
			body.put("response_format", Map.of("type", "json_object"));
		}
		try {
			JsonNode response = http.post()
					.uri("/chat/completions")
					.contentType(MediaType.APPLICATION_JSON)
					.body(body)
					.retrieve()
					.body(JsonNode.class);
			String text = response.path("choices").path(0).path("message").path("content").asString("");
			return new Completion(text, response.path("model").asString(model));
		} catch (RestClientException e) {
			throw new LlmException("LLM request failed: " + e.getMessage(), e);
		}
	}
}
