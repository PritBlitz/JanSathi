package org.jansathi.reminder;

import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.Map;
import org.jansathi.guide.Locales;
import org.springframework.stereotype.Component;

/**
 * Words for channels that can carry a free message (Telegram), in the person's language, from the
 * same locale files as the app. Until a key is in them the English here is used.
 */
@Component
public class ReminderText {

	private static final Map<String, String> ENGLISH = Map.of(
			"reminder.checklist", "JanSathi: your checklist still has documents to collect. Open the app to see them.",
			"reminder.deadline", "JanSathi: a scheme on your checklist has a last date of {date} on its official source. Open the app to check it.",
			"reminder.telegram_linked", "JanSathi reminders are on. Send /stop any time to end them.",
			"reminder.telegram_stopped", "JanSathi reminders are off, and this chat is no longer linked.",
			"reminder.telegram_link_failed", "This link has expired or was already used. Make a new one in the JanSathi app.",
			"reminder.telegram_help", "To get reminders, open JanSathi, sign in and choose Telegram.");

	private final Locales locales;
	private final ReminderProperties properties;

	public ReminderText(Locales locales, ReminderProperties properties) {
		this.locales = locales;
		this.properties = properties;
	}

	public String say(String lang, String key) {
		return locales.has(key) ? locales.t(lang, key) : ENGLISH.getOrDefault(key, key);
	}

	public String reminder(ReminderChannel.Message m) {
		String text = switch (m.kind()) {
			case CHECKLIST -> say(m.lang(), "reminder.checklist");
			case DEADLINE -> say(m.lang(), "reminder.deadline")
					.replace("{date}", DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.of(m.lang() == null ? "en" : m.lang(), "IN")).format(m.closesAt()));
		};
		return properties.publicUrl() == null ? text : text + "\n" + properties.publicUrl() + "/#/checklist";
	}
}
