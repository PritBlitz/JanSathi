package org.jansathi.account;

import org.jansathi.JansathiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Picks the SMS sender from {@code jansathi.sms.provider}. */
@Configuration
@EnableScheduling
public class AccountConfig {

	private static final Logger log = LoggerFactory.getLogger(AccountConfig.class);

	@Bean
	SmsSender smsSender(JansathiProperties properties) {
		return switch (properties.sms().provider()) {
			// Development stand-in: no SMS is sent; the API returns the code and the app shows it.
			case "dev" -> (phone, code, lang) -> {
				log.info("Dev sign-in code for ******{}: {}", phone.substring(6), code);
				return code;
			};
			default -> throw new IllegalStateException("Unknown jansathi.sms.provider: " + properties.sms().provider());
		};
	}
}
