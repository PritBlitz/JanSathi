package org.jansathi.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.ObjectMapper;

/** Picks the SMS sender from {@code jansathi.sms.provider}. {@link ProductionGuard} refuses {@code dev} in production. */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(SmsProperties.class)
public class AccountConfig {

	private static final Logger log = LoggerFactory.getLogger(AccountConfig.class);

	@Bean
	SmsSender smsSender(SmsProperties properties, ObjectMapper json) {
		return switch (properties.provider()) {
			// Development stand-in: no SMS is sent; the API returns the code and the app shows it.
			case "dev" -> (phone, code, lang) -> {
				log.info("Dev sign-in code for ******{}: {}", phone.substring(6), code);
				return code;
			};
			case "msg91" -> new Msg91SmsSender(properties, json);
			case "twilio" -> new TwilioSmsSender(properties);
			default -> throw new IllegalStateException("Unknown jansathi.sms.provider: " + properties.provider());
		};
	}
}
