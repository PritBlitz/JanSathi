package org.jansathi.account;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jansathi.JansathiProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start a production server that would be unsafe. Production means {@code
 * JANSATHI_PRODUCTION=true} or the Spring profile {@code prod} or {@code production}. The {@code dev}
 * SMS sender returns sign-in codes in the API response, so with it anyone can sign in as anyone.
 */
@Component
public class ProductionGuard {

	static final int MIN_SECRET = 32;

	public ProductionGuard(Environment environment, JansathiProperties properties) {
		if (!isProduction(environment)) {
			return;
		}
		List<String> problems = problems(properties);
		if (!problems.isEmpty()) {
			throw new IllegalStateException("Refusing to start in production: " + String.join("; ", problems));
		}
	}

	public static boolean isProduction(Environment environment) {
		return environment.getProperty("jansathi.production", Boolean.class, false)
				|| Arrays.stream(environment.getActiveProfiles()).anyMatch(p -> p.equals("prod") || p.equals("production"));
	}

	static List<String> problems(JansathiProperties properties) {
		List<String> out = new ArrayList<>();
		if ("dev".equals(properties.sms().provider())) {
			out.add("JANSATHI_SMS_PROVIDER is dev, which shows sign-in codes on screen (use msg91 or twilio)");
		}
		if (properties.secret() == null || properties.secret().length() < MIN_SECRET) {
			out.add("JANSATHI_SECRET must be at least " + MIN_SECRET + " characters");
		}
		if (properties.includeDrafts()) {
			out.add("JANSATHI_INCLUDE_DRAFTS is on, which shows unreviewed schemes");
		}
		return out;
	}
}
