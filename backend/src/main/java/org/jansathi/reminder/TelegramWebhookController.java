package org.jansathi.reminder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/**
 * Where Telegram delivers updates. It proves itself with the secret we gave it when setting the
 * webhook. Only private chats count, and only {@code /start} and {@code /stop}: the bot reads nothing
 * else, and keeps nothing of what people write to it. Answers are always 200 to a good secret, or
 * Telegram would keep retrying the update.
 */
@RestController
public class TelegramWebhookController {

	private final Reminders reminders;
	private final TelegramChannel telegram;
	private final ReminderText text;
	private final byte[] secret;

	public TelegramWebhookController(Reminders reminders, TelegramChannel telegram, ReminderText text, ReminderProperties properties) {
		this.reminders = reminders;
		this.telegram = telegram;
		this.text = text;
		String s = properties.telegram().webhookSecret();
		this.secret = s == null || !telegram.configured() ? null : s.getBytes(StandardCharsets.UTF_8);
	}

	@PostMapping("/api/v1/reminders/telegram/webhook")
	public void update(@RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false) String given, @RequestBody JsonNode update) {
		if (secret == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		if (given == null || !MessageDigest.isEqual(secret, given.getBytes(StandardCharsets.UTF_8))) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
		// The person blocked the bot: the chat is gone from our side too.
		JsonNode member = update.path("my_chat_member");
		if ("kicked".equals(member.path("new_chat_member").path("status").asString(""))) {
			reminders.stopTelegram(member.path("chat").path("id").asString(""));
			return;
		}
		JsonNode message = update.path("message");
		if (!"private".equals(message.path("chat").path("type").asString(""))) {
			return;
		}
		String chatId = message.path("chat").path("id").asString("");
		String command = message.path("text").asString("").strip();
		if (chatId.isEmpty()) {
			return;
		}
		if (command.equals("/stop")) {
			String lang = reminders.langOfChat(chatId);
			reminders.stopTelegram(chatId);
			telegram.say(chatId, text.say(lang, "reminder.telegram_stopped"));
		} else if (command.startsWith("/start ")) {
			boolean linked = reminders.linkTelegram(command.substring(7).strip(), chatId);
			telegram.say(chatId, text.say(reminders.langOfChat(chatId), linked ? "reminder.telegram_linked" : "reminder.telegram_link_failed"));
		} else if (command.startsWith("/start")) {
			telegram.say(chatId, text.say("en", "reminder.telegram_help"));
		}
	}
}
