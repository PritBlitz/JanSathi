package org.jansathi.reminder;

import java.util.Map;
import org.jansathi.account.ProviderHttp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Telegram's Bot API. The address is the private chat id, which the bot learns from {@code /start}. */
@Component
public class TelegramChannel implements ReminderChannel {

	private static final Logger log = LoggerFactory.getLogger(TelegramChannel.class);

	private final ReminderProperties.Telegram properties;
	private final ReminderText text;
	private final ObjectMapper json;

	public TelegramChannel(ReminderProperties properties, ReminderText text, ObjectMapper json) {
		this.properties = properties.telegram();
		this.text = text;
		this.json = json;
	}

	@Override
	public String id() {
		return "telegram";
	}

	@Override
	public boolean configured() {
		return properties.configured();
	}

	/** The link that opens the bot and hands it the one-time token. */
	String startLink(String token) {
		return "https://t.me/" + properties.botUsername() + "?start=" + token;
	}

	Result say(String chatId, String message) {
		String body = json.writeValueAsString(Map.of("chat_id", chatId, "text", message, "disable_web_page_preview", true));
		ProviderHttp.Answer a = ProviderHttp.post(properties.baseUrl() + "/bot" + properties.botToken() + "/sendMessage", "application/json", body, Map.of());
		if (a.ok()) {
			return Result.SENT;
		}
		// 403: the person blocked the bot. 400 with "chat not found" is a chat that no longer exists.
		if (a.status() == 403 || (a.status() == 400 && a.body().contains("chat not found"))) {
			return Result.GONE;
		}
		log.warn("Telegram answered {}", a.status());
		return Result.FAILED;
	}

	@Override
	public Result send(String address, Message message) {
		return say(address, text.reminder(message));
	}
}
