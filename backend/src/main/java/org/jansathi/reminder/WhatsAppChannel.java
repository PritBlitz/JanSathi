package org.jansathi.reminder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jansathi.account.ProviderHttp;
import org.jansathi.account.SmsText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * WhatsApp Cloud API. A business may start a conversation only with an approved template, so the
 * words live in Meta's template manager and this sends the template's name, language and the date.
 * The address is the person's own number. Never run against the live service here.
 */
@Component
public class WhatsAppChannel implements ReminderChannel {

	private static final Logger log = LoggerFactory.getLogger(WhatsAppChannel.class);

	private final ReminderProperties.WhatsApp properties;
	private final ObjectMapper json;

	public WhatsAppChannel(ReminderProperties properties, ObjectMapper json) {
		this.properties = properties.whatsapp();
		this.json = json;
	}

	@Override
	public String id() {
		return "whatsapp";
	}

	@Override
	public boolean configured() {
		return properties.configured();
	}

	@Override
	public Result send(String address, Message message) {
		boolean deadline = message.kind() == Kind.DEADLINE;
		Map<String, Object> template = new LinkedHashMap<>();
		template.put("name", deadline ? properties.deadlineTemplate() : properties.checklistTemplate());
		template.put("language", Map.of("code", properties.languages().contains(message.lang()) ? message.lang() : "en"));
		if (deadline) {
			template.put("components", List.of(Map.of("type", "body", "parameters", List.of(Map.of("type", "text", "text", SmsText.date(message.closesAt()))))));
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("messaging_product", "whatsapp");
		body.put("recipient_type", "individual");
		body.put("to", "91" + address);
		body.put("type", "template");
		body.put("template", template);
		ProviderHttp.Answer a = ProviderHttp.post(properties.baseUrl() + "/" + properties.apiVersion() + "/" + properties.phoneNumberId() + "/messages",
				"application/json", json.writeValueAsString(body), Map.of("Authorization", "Bearer " + properties.accessToken()));
		if (a.ok()) {
			return Result.SENT;
		}
		log.warn("WhatsApp answered {}", a.status());
		return Result.FAILED;
	}
}
