package org.jansathi.account;

import java.time.LocalDate;

/** Sends text messages to a phone. A provider behind an interface (docs/ARCHITECTURE.md §6). */
public interface SmsSender {

	/** The two reminders. Fixed text, because DLT templates are: see {@link SmsText}. */
	enum Reminder {
		CHECKLIST,
		DEADLINE
	}

	/**
	 * @return the code itself when this sender is the development stand-in (shown on screen), or
	 *     null when a real SMS was sent
	 * @throws org.jansathi.web.ProviderUnavailableException when the provider refuses or can't be reached
	 */
	String sendCode(String phone, String code, String lang);

	/** True when this sender has reminder templates. */
	default boolean sendsReminders() {
		return false;
	}

	/**
	 * @param closesAt the date for {@link Reminder#DEADLINE}, otherwise null
	 * @return true when the provider took the message
	 */
	default boolean sendReminder(String phone, Reminder reminder, LocalDate closesAt) {
		return false;
	}
}
