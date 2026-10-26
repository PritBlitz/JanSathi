package org.jansathi.guide;

import org.jansathi.account.Accounts.User;
import org.jansathi.provider.llm.LlmProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Talking to Saathi, the AI guide. Signed-in only: the guide remembers the conversation. */
@RestController
@RequestMapping("/api/v1/guide")
public class GuideController {

	private final GuideService guide;
	private final LlmProvider llm;

	public GuideController(GuideService guide, LlmProvider llm) {
		this.guide = guide;
		this.llm = llm;
	}

	/**
	 * @param text what the person typed or said
	 * @param answer an answer tapped on a question card: {@code {field, value}}
	 */
	public record Turn(String text, JsonNode answer, String lang) {}

	public record Status(boolean ai) {}

	/** Whether the guide has a model (otherwise it steps through the questions on its own). */
	@GetMapping("/status")
	public Status status() {
		return new Status(llm.enabled());
	}

	@PostMapping("/messages")
	public GuideService.Reply message(User user, @RequestBody Turn turn) {
		return guide.turn(user, turn.text(), turn.answer(), turn.lang());
	}
}
