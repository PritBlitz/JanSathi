package org.jansathi.reminder;

import org.jansathi.account.SmsSender;
import org.springframework.stereotype.Component;

/** SMS through the sign-in provider, on its own DLT templates. The address is the person's own number. */
@Component
public class SmsChannel implements ReminderChannel {

	private final SmsSender sender;

	public SmsChannel(SmsSender sender) {
		this.sender = sender;
	}

	@Override
	public String id() {
		return "sms";
	}

	@Override
	public boolean configured() {
		return sender.sendsReminders();
	}

	@Override
	public Result send(String address, Message message) {
		SmsSender.Reminder r = message.kind() == Kind.DEADLINE ? SmsSender.Reminder.DEADLINE : SmsSender.Reminder.CHECKLIST;
		return sender.sendReminder(address, r, message.closesAt()) ? Result.SENT : Result.FAILED;
	}
}
