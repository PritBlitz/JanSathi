package org.jansathi.provider.llm;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * A language model behind an interface (docs/ARCHITECTURE.md §6), so providers can be swapped as
 * free tiers change. The model only ever explains retrieved, cited passages and the rules engine's
 * results; it never decides a match result.
 */
public interface LlmProvider {

	/** False when no model is configured: the assistant then returns cited passages only. */
	boolean enabled();

	/** Model id recorded with every answer, so regressions can be traced. */
	String modelId();

	/**
	 * One completion without tools.
	 *
	 * @throws LlmException when the provider fails or times out; callers degrade, never crash
	 */
	default Completion complete(String system, List<Message> messages, CompletionOptions options) {
		Turn turn = chat(system, messages, List.of(), options);
		return new Completion(turn.text() == null ? "" : turn.text(), turn.model());
	}

	/**
	 * One step of a conversation that may call tools. The caller runs any requested tools, appends
	 * {@link Message#assistantCalls} and one {@link Message#toolResult} per call, and asks again.
	 *
	 * @throws LlmException when the provider fails or times out
	 */
	Turn chat(String system, List<Message> messages, List<Tool> tools, CompletionOptions options);

	/**
	 * A message in the conversation.
	 *
	 * @param role user, assistant or tool
	 * @param toolCalls set on an assistant message that asked for tools
	 * @param toolCallId set on a tool result: the call it answers
	 */
	record Message(String role, String content, List<ToolCall> toolCalls, String toolCallId) {

		public static Message user(String content) {
			return new Message("user", content, List.of(), null);
		}

		public static Message assistant(String content) {
			return new Message("assistant", content, List.of(), null);
		}

		public static Message assistantCalls(List<ToolCall> calls) {
			return new Message("assistant", null, List.copyOf(calls), null);
		}

		public static Message toolResult(String toolCallId, String content) {
			return new Message("tool", content, List.of(), toolCallId);
		}
	}

	/** A tool the model may call. {@code parameters} is a JSON Schema object. */
	record Tool(String name, String description, JsonNode parameters) {}

	/** @param arguments the arguments as a JSON object */
	record ToolCall(String id, String name, JsonNode arguments) {}

	/** The model's reply: either text, or tool calls to run first. */
	record Turn(String text, List<ToolCall> toolCalls, String model) {

		public boolean wantsTools() {
			return toolCalls != null && !toolCalls.isEmpty();
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
