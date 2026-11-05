package org.jansathi.reminder;

import java.time.LocalDate;

/**
 * One way of reaching a person (brief §6.5). A provider behind an interface (docs/ARCHITECTURE.md
 * §6): the job in {@link Reminders} knows nothing about any of them.
 */
public interface ReminderChannel {

	enum Result {
		SENT,
		/** The person blocked us or the address is dead: forget it. */
		GONE,
		/** A passing failure: keep the address and try next time. */
		FAILED
	}

	enum Kind {
		CHECKLIST,
		DEADLINE
	}

	/**
	 * What to say. It never names a scheme: messages cross third parties and show on lock screens,
	 * and a scheme's name can reveal caste, disability or income. The app shows which scheme, and the
	 * official source for the date.
	 *
	 * @param closesAt the last date for {@link Kind#DEADLINE}, otherwise null
	 */
	record Message(Kind kind, String lang, LocalDate closesAt) {}

	/** {@code web_push}, {@code telegram}, {@code sms} or {@code whatsapp}. */
	String id();

	/** False until the channel's settings are complete; it is then not offered at all. */
	boolean configured();

	Result send(String address, Message message);
}
