package org.jansathi.web;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code jansathi.web} in application.yml.
 *
 * @param trustedProxies addresses or CIDR ranges of our own reverse proxies. {@code
 *     X-Forwarded-For} is believed only from these; empty means the socket address is the client
 * @param rateLimitEnabled per-address limits on the API
 * @param metricsToken bearer token for {@code /actuator/prometheus}; blank switches it off
 * @param sentryDsn where error reports go; blank switches them off
 * @param environment shown on error reports, e.g. production
 */
@ConfigurationProperties("jansathi.web")
public record WebProperties(List<String> trustedProxies, Boolean rateLimitEnabled, String metricsToken, String sentryDsn, String environment) {

	public WebProperties {
		trustedProxies = trustedProxies == null ? List.of() : trustedProxies.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
		rateLimitEnabled = rateLimitEnabled == null || rateLimitEnabled;
		metricsToken = metricsToken == null || metricsToken.isBlank() ? null : metricsToken;
		sentryDsn = sentryDsn == null || sentryDsn.isBlank() ? null : sentryDsn;
		environment = environment == null || environment.isBlank() ? "development" : environment;
	}
}
