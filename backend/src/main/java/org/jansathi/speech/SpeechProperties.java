package org.jansathi.speech;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code jansathi.speech.*}.
 *
 * @param provider {@code none} (default: the app keeps to browser speech) or {@code ml-service}
 * @param baseUrl the internal ML service, e.g. http://ml:8090
 * @param token shared service token; may be blank on a private network
 * @param timeout per request, long enough for a model's first load
 * @param maxTextChars longest text to read aloud in one request
 * @param maxAudioBytes largest recording; 5 MB is minutes of compressed speech
 * @param perMinute requests per client address per minute, both directions together
 * @param maxConcurrent speech requests running at once; the models are CPU-bound
 */
@ConfigurationProperties("jansathi.speech")
public record SpeechProperties(
		String provider, String baseUrl, String token, Duration timeout, Integer maxTextChars, Integer maxAudioBytes, Integer perMinute, Integer maxConcurrent) {

	public SpeechProperties {
		provider = provider == null || provider.isBlank() ? "none" : provider;
		token = token == null || token.isBlank() ? null : token;
		timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
		maxTextChars = maxTextChars == null ? 1000 : maxTextChars;
		maxAudioBytes = maxAudioBytes == null ? 5_000_000 : maxAudioBytes;
		perMinute = perMinute == null ? 20 : perMinute;
		maxConcurrent = maxConcurrent == null ? 2 : maxConcurrent;
	}
}
