package org.jansathi.reminder;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code jansathi.reminders} in application.yml. Each channel is off until its
 * settings are complete.
 *
 * @param publicUrl the app's own address, for the link in messages; blank sends no link
 */
@ConfigurationProperties("jansathi.reminders")
public record ReminderProperties(String publicUrl, Telegram telegram, WhatsApp whatsapp) {

	public ReminderProperties {
		publicUrl = blank(publicUrl);
		telegram = telegram == null ? new Telegram(null, null, null, null) : telegram;
		whatsapp = whatsapp == null ? new WhatsApp(null, null, null, null, null, null, null) : whatsapp;
	}

	private static String blank(String s) {
		return s == null || s.isBlank() ? null : s;
	}

	/**
	 * @param botToken from BotFather
	 * @param botUsername without the @, for the {@code t.me} link
	 * @param webhookSecret given to Telegram as {@code secret_token}; it comes back on every update
	 * @param baseUrl the Bot API root; only tests change it
	 */
	public record Telegram(String botToken, String botUsername, String webhookSecret, String baseUrl) {

		public Telegram {
			botToken = blank(botToken);
			botUsername = blank(botUsername);
			webhookSecret = blank(webhookSecret);
			baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.telegram.org" : baseUrl;
		}

		public boolean configured() {
			return botToken != null && botUsername != null && webhookSecret != null;
		}
	}

	/**
	 * @param accessToken a system user's permanent token
	 * @param phoneNumberId the sending number's id (not the number)
	 * @param checklistTemplate approved template with no variables
	 * @param deadlineTemplate approved template with one body variable, the date
	 * @param languages codes the templates are approved in; others get {@code en}
	 * @param apiVersion Graph API version, e.g. v23.0
	 * @param baseUrl the Graph API root; only tests change it
	 */
	public record WhatsApp(String accessToken, String phoneNumberId, String checklistTemplate, String deadlineTemplate, List<String> languages, String apiVersion, String baseUrl) {

		public WhatsApp {
			accessToken = blank(accessToken);
			phoneNumberId = blank(phoneNumberId);
			checklistTemplate = blank(checklistTemplate);
			deadlineTemplate = blank(deadlineTemplate);
			languages = languages == null || languages.isEmpty() ? List.of("en") : List.copyOf(languages);
			apiVersion = apiVersion == null || apiVersion.isBlank() ? "v23.0" : apiVersion;
			baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://graph.facebook.com" : baseUrl;
		}

		public boolean configured() {
			return accessToken != null && phoneNumberId != null && checklistTemplate != null && deadlineTemplate != null;
		}
	}
}
