package org.jansathi.provider.llm;

import java.util.List;

/**
 * A language model behind an interface (docs/ARCHITECTURE.md §6), so providers can be swapped as
 * free tiers change. The model only ever explains retrieved, cited passages; it never decides a
 * match result.
 */
public interface LlmProvider {

	/** False when no model is configured: the assistant then returns cited passages only. */
	boolean enabled();

	/** Model id recorded with every answer, so regressions can be traced. */
	String modelId();

	/**
	 * @throws LlmException when the provider fails or times out; callers degrade, never crash
	 */
	Completion complete(String system, List<Message> messages, CompletionOptions options);

	record Message(String role, String content) {

		public static Message user(String content) {
			return new Message("user", content);
		}
	}

	/** @param jsonOutput ask for a JSON object (JSON mode where the provider supports it) */
	record CompletionOptions(int maxTokens, double temperature, boolean jsonOutput) {}

	record Completion(String text, String model) {}

	class LlmException extends RuntimeException {

		public LlmException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
