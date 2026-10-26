package org.jansathi.provider.llm;

import java.util.List;
import org.jansathi.JansathiProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Picks the LLM provider from {@code jansathi.llm.provider}. Default: none. */
@Configuration
public class LlmConfig {

	@Bean
	LlmProvider llmProvider(JansathiProperties properties, RestClient.Builder builder) {
		JansathiProperties.Llm llm = properties.llm();
		return switch (llm.provider()) {
			case "none" -> DISABLED;
			case "openai-compatible" -> new OpenAiCompatibleLlmProvider(llm, builder);
			default -> throw new IllegalStateException("Unknown jansathi.llm.provider: " + llm.provider());
		};
	}

	/** No model: the assistant answers with cited passages only. */
	public static final LlmProvider DISABLED = new LlmProvider() {
		@Override
		public boolean enabled() {
			return false;
		}

		@Override
		public String modelId() {
			return null;
		}

		@Override
		public Turn chat(String system, List<Message> messages, List<Tool> tools, CompletionOptions options) {
			throw new LlmException("No LLM provider is configured", null);
		}
	};
}
