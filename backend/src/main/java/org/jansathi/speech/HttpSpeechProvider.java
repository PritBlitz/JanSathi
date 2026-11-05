package org.jansathi.speech;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/** Calls the internal ML service's {@code /v1/status}, {@code /v1/tts} and {@code /v1/stt} (ml/README.md). */
public class HttpSpeechProvider implements SpeechProvider {

	/** The app asks on every load; the answer changes only when the service does. */
	private static final Duration STATUS_TTL = Duration.ofSeconds(30);

	private final RestClient http;
	private final RestClient statusHttp;
	private volatile Languages cached = Languages.NONE;
	private volatile Instant cachedAt = Instant.MIN;

	public HttpSpeechProvider(SpeechProperties settings, RestClient.Builder builder) {
		if (settings.baseUrl() == null || settings.baseUrl().isBlank()) {
			throw new IllegalStateException("jansathi.speech.base-url is required for the ml-service provider");
		}
		this.http = client(settings, builder, settings.timeout());
		this.statusHttp = client(settings, builder, Duration.ofSeconds(2));
	}

	private static RestClient client(SpeechProperties settings, RestClient.Builder builder, Duration timeout) {
		// The ML service speaks HTTP/1.1; the JDK client's h2c upgrade attempt is wasted on it.
		HttpClient jdk = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
		factory.setReadTimeout(timeout);
		RestClient.Builder b = builder.clone().baseUrl(settings.baseUrl()).requestFactory(factory);
		if (settings.token() != null) {
			b.defaultHeader("Authorization", "Bearer " + settings.token());
		}
		return b.build();
	}

	@Override
	public boolean enabled() {
		return true;
	}

	@Override
	public Languages languages() {
		if (Instant.now().isBefore(cachedAt.plus(STATUS_TTL))) {
			return cached;
		}
		Languages now;
		try {
			JsonNode reply = statusHttp.get().uri("/v1/status").retrieve().body(JsonNode.class);
			now = reply == null ? Languages.NONE : new Languages(strings(reply.path("tts")), strings(reply.path("stt")));
		} catch (RestClientException e) {
			now = Languages.NONE; // the app keeps to browser speech
		}
		cached = now;
		cachedAt = Instant.now();
		return now;
	}

	private static List<String> strings(JsonNode array) {
		List<String> out = new ArrayList<>();
		array.forEach(n -> out.add(n.asString()));
		return List.copyOf(out);
	}

	@Override
	public byte[] synthesize(String text, String lang) {
		try {
			byte[] wav = http.post().uri("/v1/tts").contentType(MediaType.APPLICATION_JSON)
					.body(Map.of("text", text, "lang", lang)).retrieve().body(byte[].class);
			if (wav == null || wav.length == 0) {
				throw new SpeechUnavailableException("The speech service sent no audio", null);
			}
			return wav;
		} catch (HttpClientErrorException e) {
			throw new SpeechRejectedException("The speech service refused the text");
		} catch (RestClientException e) {
			throw new SpeechUnavailableException("The speech service failed", e);
		}
	}

	@Override
	public String transcribe(byte[] audio, String lang) {
		try {
			JsonNode reply = http.post().uri(u -> u.path("/v1/stt").queryParam("lang", lang).build())
					.contentType(MediaType.APPLICATION_OCTET_STREAM).body(audio).retrieve().body(JsonNode.class);
			if (reply == null || !reply.path("text").isString()) {
				throw new SpeechUnavailableException("The speech service sent no text", null);
			}
			return reply.path("text").asString();
		} catch (HttpClientErrorException e) {
			throw new SpeechRejectedException("The speech service could not read the audio");
		} catch (RestClientException e) {
			throw new SpeechUnavailableException("The speech service failed", e);
		}
	}

	/** Picks the provider from {@code jansathi.speech.provider}. Default: none. */
	@Configuration
	@EnableConfigurationProperties(SpeechProperties.class)
	static class Config {

		@Bean
		SpeechProvider speechProvider(SpeechProperties properties, RestClient.Builder builder) {
			return switch (properties.provider()) {
				case "none" -> SpeechProvider.DISABLED;
				case "ml-service" -> new HttpSpeechProvider(properties, builder);
				default -> throw new IllegalStateException("Unknown jansathi.speech.provider: " + properties.provider());
			};
		}
	}
}
