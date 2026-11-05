package org.jansathi.account;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jansathi.web.ProviderUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Twilio's Messages API through a Messaging Service. For India the DLT entity and template ids are
 * given to Twilio at registration, so each message is only the text, which must equal the registered
 * template ({@link SmsText}). Never run against the live service here: docs/OPERATIONS.md §5.
 */
public class TwilioSmsSender implements SmsSender {

	private static final Logger log = LoggerFactory.getLogger(TwilioSmsSender.class);

	private final SmsProperties properties;
	private final String authorization;

	public TwilioSmsSender(SmsProperties properties) {
		SmsProperties.Twilio t = properties.twilio();
		if (t.accountSid() == null || t.authToken() == null || t.messagingServiceSid() == null) {
			throw new IllegalStateException("Twilio needs JANSATHI_SMS_TWILIO_ACCOUNT_SID, _AUTH_TOKEN and _MESSAGING_SERVICE_SID");
		}
		this.properties = properties;
		this.authorization = "Basic " + Base64.getEncoder().encodeToString((t.accountSid() + ":" + t.authToken()).getBytes(StandardCharsets.UTF_8));
	}

	private boolean send(String phone, String text) {
		SmsProperties.Twilio t = properties.twilio();
		Map<String, String> form = new LinkedHashMap<>();
		form.put("To", "+91" + phone);
		form.put("MessagingServiceSid", t.messagingServiceSid());
		form.put("Body", text);
		ProviderHttp.Answer a = ProviderHttp.post(t.baseUrl() + "/Accounts/" + t.accountSid() + "/Messages.json",
				"application/x-www-form-urlencoded", ProviderHttp.form(form), Map.of("Authorization", authorization));
		if (!a.ok()) {
			log.warn("Twilio answered {}", a.status());
		}
		return a.ok();
	}

	@Override
	public String sendCode(String phone, String code, String lang) {
		if (!send(phone, SmsText.code(code))) {
			throw new ProviderUnavailableException("Twilio did not take the sign-in code");
		}
		return null;
	}

	@Override
	public boolean sendsReminders() {
		return properties.remindersConfigured();
	}

	@Override
	public boolean sendReminder(String phone, Reminder reminder, LocalDate closesAt) {
		return send(phone, SmsText.reminder(reminder, closesAt));
	}
}
