package org.jansathi.assist;

import org.jansathi.web.BadRequestException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/ask}: cited passages for a question in the user's own words. Today this is
 * retrieval only (docs/RAG_ASSISTANT.md steps 1, 3, 4 and 7). The LLM explanation step is
 * planned; until then {@code answer} is always null and the client shows the passages.
 */
@RestController
@RequestMapping("/api/v1")
public class AssistController {

	private static final int MAX_QUESTION = 500;

	private final AssistService assist;

	public AssistController(AssistService assist) {
		this.assist = assist;
	}

	public record AskRequest(String question, String lang, String scheme, String state) {}

	@PostMapping("/ask")
	public AssistService.AskResponse ask(@RequestBody AskRequest request) {
		if (request.question() == null || request.question().isBlank()) {
			throw new BadRequestException("question is required");
		}
		if (request.question().length() > MAX_QUESTION) {
			throw new BadRequestException("question must be at most " + MAX_QUESTION + " characters");
		}
		return assist.ask(request.question(), request.lang(), request.scheme(), request.state());
	}
}
