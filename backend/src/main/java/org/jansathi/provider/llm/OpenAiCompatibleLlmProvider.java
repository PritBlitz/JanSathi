package org.jansathi.provider.llm;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jansathi.JansathiProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Any provider with the OpenAI chat-completions API, including tool calling: Google Gemini (its
 * OpenAI-compatible endpoint), Groq, a local Ollama ({@code /v1}), and others. Plain HTTP, no SDK,
 * so swapping providers is configuration only.
 */
public class OpenAiCompatibleLlmProvider implements LlmProvider {

	private static final ObjectMapper JSON = JsonMapper.builder().build();

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
	public Turn chat(String system, List<Message> messages, List<Tool> tools, CompletionOptions options) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", model);
		List<Map<String, Object>> all = new ArrayList<>();
		all.add(Map.of("role", "system", "content", system));
		messages.forEach(m -> all.add(wire(m)));
		body.put("messages", all);
		body.put("temperature", options.temperature());
		body.put("max_tokens", options.maxTokens());
		if (!tools.isEmpty()) {
			body.put("tools", tools.stream().map(t -> Map.of("type", "function", "function",
					Map.of("name", t.name(), "description", t.description(), "parameters", t.parameters()))).toList());
		} else if (options.jsonOutput()) {
			body.put("response_format", Map.of("type", "json_object"));
		}
		try {
			JsonNode response = http.post()
					.uri("/chat/completions")
					.contentType(MediaType.APPLICATION_JSON)
					.body(body)
					.retrieve()
					.body(JsonNode.class);
			JsonNode message = response.path("choices").path(0).path("message");
			List<ToolCall> calls = new ArrayList<>();
			for (JsonNode c : message.path("tool_calls")) {
				calls.add(new ToolCall(c.path("id").asString(""), c.path("function").path("name").asString(""),
						parseArguments(c.path("function").path("arguments"))));
			}
			String text = message.path("content").isString() ? message.get("content").asString() : null;
			return new Turn(text, calls, response.path("model").asString(model));
		} catch (RestClientException e) {
			throw new LlmException("LLM request failed: " + e.getMessage(), e);
		}
	}

	private static Map<String, Object> wire(Message m) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("role", m.role());
		out.put("content", m.content());
		if (!m.toolCalls().isEmpty()) {
			out.put("tool_calls", m.toolCalls().stream().map(c -> Map.of("id", c.id(), "type", "function",
					"function", Map.of("name", c.name(), "arguments", JSON.writeValueAsString(c.arguments())))).toList());
		}
		if (m.toolCallId() != null) {
			out.put("tool_call_id", m.toolCallId());
		}
		return out;
	}

	/** Arguments arrive as a JSON string (OpenAI) or an object (some compatible servers). */
	private static JsonNode parseArguments(JsonNode raw) {
		if (raw.isObject()) {
			return raw;
		}
		try {
			JsonNode parsed = JSON.readTree(raw.asString("{}"));
			return parsed.isObject() ? parsed : JsonNodeFactory.instance.objectNode();
		} catch (JacksonException e) {
			return JsonNodeFactory.instance.objectNode();
		}
	}
}
