package org.jansathi.account;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The exact words of each SMS. A DLT-registered template can't vary, so these are in English and are
 * registered as written (docs/OPERATIONS.md §5 lists them with their {@code {#var#}} forms). A
 * reminder never names a scheme: the person's phone screen and the SMS route are not private, and a
 * scheme name can say something sensitive about them. The app shows which scheme, with its source.
 */
public final class SmsText {

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

	private SmsText() {}

	public static String date(LocalDate d) {
		return DATE.format(d);
	}

	public static String code(String code) {
		return "Your JanSathi sign-in code is " + code + ". It works for 5 minutes. Do not share it with anyone.";
	}

	public static String reminder(SmsSender.Reminder reminder, LocalDate closesAt) {
		return switch (reminder) {
			case CHECKLIST -> "JanSathi: your checklist still has documents to collect. Open the app to see them.";
			case DEADLINE -> "JanSathi: a scheme on your checklist has a last date of " + date(closesAt) + " on its official source. Open the app to check it.";
		};
	}
}
