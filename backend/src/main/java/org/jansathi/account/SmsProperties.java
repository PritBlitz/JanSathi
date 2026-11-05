package org.jansathi.account;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code jansathi.sms} in application.yml. In India every commercial SMS must match a
 * template registered on a DLT portal, under a registered sender and principal entity.
 *
 * @param provider {@code dev} (no SMS; the code is shown on screen), {@code msg91} or {@code twilio}
 * @param otpTemplateId DLT template for sign-in codes. MSG91: the template id of the flow that
 *     carries it
 * @param checklistTemplateId DLT template for checklist reminders; blank means no SMS reminders
 * @param deadlineTemplateId DLT template for deadline reminders; blank means no SMS reminders
 */
@ConfigurationProperties("jansathi.sms")
public record SmsProperties(String provider, String otpTemplateId, String checklistTemplateId, String deadlineTemplateId, Msg91 msg91, Twilio twilio) {

	public SmsProperties {
		provider = provider == null || provider.isBlank() ? "dev" : provider;
		otpTemplateId = blank(otpTemplateId);
		checklistTemplateId = blank(checklistTemplateId);
		deadlineTemplateId = blank(deadlineTemplateId);
		msg91 = msg91 == null ? new Msg91(null, null) : msg91;
		twilio = twilio == null ? new Twilio(null, null, null, null) : twilio;
	}

	static String blank(String s) {
		return s == null || s.isBlank() ? null : s;
	}

	public boolean remindersConfigured() {
		return checklistTemplateId != null && deadlineTemplateId != null;
	}

	/**
	 * @param authKey account key, sent in the {@code authkey} header
	 * @param baseUrl the API root; only tests change it
	 */
	public record Msg91(String authKey, String baseUrl) {

		public Msg91 {
			authKey = blank(authKey);
			baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://control.msg91.com/api/v5" : baseUrl;
		}
	}

	/**
	 * @param accountSid the {@code AC...} account id
	 * @param authToken its token
	 * @param messagingServiceSid the {@code MG...} service holding the DLT-registered sender. Twilio
	 *     takes the entity and template ids in its India registration, not on each message, so the
	 *     text sent must match the registered template exactly
	 * @param baseUrl the API root; only tests change it
	 */
	public record Twilio(String accountSid, String authToken, String messagingServiceSid, String baseUrl) {

		public Twilio {
			accountSid = blank(accountSid);
			authToken = blank(authToken);
			messagingServiceSid = blank(messagingServiceSid);
			baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.twilio.com/2010-04-01" : baseUrl;
		}
	}
}
