package org.jansathi.account;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.jansathi.web.ProviderUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * MSG91's flow API: the template (already tied to its DLT id on the MSG91 side) plus variables. The
 * template's words live with MSG91, so the variable names here must match it: {@code code} and
 * {@code date}. Never run against the live service here: docs/OPERATIONS.md §5.
 */
public class Msg91SmsSender implements SmsSender {

	private static final Logger log = LoggerFactory.getLogger(Msg91SmsSender.class);

	private final SmsProperties properties;
	private final ObjectMapper json;

	public Msg91SmsSender(SmsProperties properties, ObjectMapper json) {
		if (properties.msg91().authKey() == null || properties.otpTemplateId() == null) {
			throw new IllegalStateException("MSG91 needs JANSATHI_SMS_MSG91_AUTH_KEY and JANSATHI_SMS_OTP_TEMPLATE_ID");
		}
		this.properties = properties;
		this.json = json;
	}

	private boolean send(String template, String phone, Map<String, String> vars) {
		var recipient = new java.util.LinkedHashMap<String, String>();
		recipient.put("mobiles", "91" + phone);
		recipient.putAll(vars);
		String body = json.writeValueAsString(Map.of("template_id", template, "short_url", "0", "recipients", List.of(recipient)));
		ProviderHttp.Answer a = ProviderHttp.post(properties.msg91().baseUrl() + "/flow", "application/json", body, Map.of("authkey", properties.msg91().authKey()));
		if (!a.ok()) {
			log.warn("MSG91 answered {}", a.status());
		}
		return a.ok();
	}

	@Override
	public String sendCode(String phone, String code, String lang) {
		if (!send(properties.otpTemplateId(), phone, Map.of("code", code))) {
			throw new ProviderUnavailableException("MSG91 did not take the sign-in code");
		}
		return null;
	}

	@Override
	public boolean sendsReminders() {
		return properties.remindersConfigured();
	}

	@Override
	public boolean sendReminder(String phone, Reminder reminder, LocalDate closesAt) {
		return switch (reminder) {
			case CHECKLIST -> send(properties.checklistTemplateId(), phone, Map.of());
			case DEADLINE -> send(properties.deadlineTemplateId(), phone, Map.of("date", SmsText.date(closesAt)));
		};
	}
}
