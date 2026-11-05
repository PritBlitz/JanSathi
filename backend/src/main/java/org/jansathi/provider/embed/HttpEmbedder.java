package org.jansathi.provider.embed;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jansathi.JansathiProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/** Calls the internal ML service's {@code POST /v1/embed} (ml/README.md). */
public class HttpEmbedder implements Embedder {

	/** Each request stays well inside the timeout on a CPU. */
	private static final int BATCH = 16;

	private final RestClient http;
	private final String model;

	public HttpEmbedder(JansathiProperties.Embed settings, RestClient.Builder builder) {
		if (settings.baseUrl() == null || settings.baseUrl().isBlank()) {
			throw new IllegalStateException("jansathi.embed.base-url is required for the ml-service provider");
		}
		// The ML service speaks HTTP/1.1; the JDK client's h2c upgrade attempt is wasted on it.
		HttpClient jdk = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
		factory.setReadTimeout(settings.timeout());
		RestClient.Builder b = builder.clone().baseUrl(settings.baseUrl()).requestFactory(factory);
		if (settings.token() != null) {
			b.defaultHeader("Authorization", "Bearer " + settings.token());
		}
		this.http = b.build();
		this.model = settings.model();
	}

	@Override
	public boolean enabled() {
		return true;
	}

	@Override
	public String modelId() {
		return model;
	}

	@Override
	public List<float[]> embed(List<String> texts, Kind kind) {
		List<float[]> out = new ArrayList<>(texts.size());
		for (int from = 0; from < texts.size(); from += BATCH) {
			out.addAll(batch(texts.subList(from, Math.min(from + BATCH, texts.size())), kind));
		}
		return out;
	}

	private List<float[]> batch(List<String> texts, Kind kind) {
		JsonNode reply;
		try {
			reply = http.post().uri("/v1/embed").contentType(MediaType.APPLICATION_JSON)
					.body(Map.of("texts", texts, "kind", kind.name().toLowerCase(Locale.ROOT)))
					.retrieve().body(JsonNode.class);
		} catch (RestClientException e) {
			throw new EmbedException("The embedding service failed", e);
		}
		JsonNode vectors = reply == null ? null : reply.path("vectors");
		if (vectors == null || !vectors.isArray() || vectors.size() != texts.size()) {
			throw new EmbedException("The embedding service sent a wrong number of vectors", null);
		}
		List<float[]> out = new ArrayList<>(vectors.size());
		for (JsonNode v : vectors) {
			float[] f = new float[v.size()];
			for (int i = 0; i < f.length; i++) {
				f[i] = v.get(i).floatValue();
			}
			out.add(f);
		}
		return out;
	}

	/** Picks the embedder from {@code jansathi.embed.provider}. Default: none. */
	@Configuration
	static class Config {

		@Bean
		Embedder embedder(JansathiProperties properties, RestClient.Builder builder) {
			JansathiProperties.Embed e = properties.embed();
			return switch (e.provider()) {
				case "none" -> Embedder.DISABLED;
				case "ml-service" -> new HttpEmbedder(e, builder);
				default -> throw new IllegalStateException("Unknown jansathi.embed.provider: " + e.provider());
			};
		}
	}
}
