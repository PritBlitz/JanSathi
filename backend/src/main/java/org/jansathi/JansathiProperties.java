package org.jansathi;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code jansathi.*} in application.yml.
 *
 * @param bundleDir directory holding bundle.json, bundle.preview.json and states/ (written by
 *     tools/build-bundle.mjs); null reads the copy packaged in the jar under {@code bundle/}
 * @param previewFallback serve draft schemes, flagged as preview, while no scheme is published
 * @param includeDrafts always serve the draft bundle (staging, tests); never in production
 * @param corsOrigins browser origins allowed to call the API (the frontend dev server, the PWA host)
 * @param adminToken shared bearer token for {@code /api/v1/admin/**}; blank switches the admin API off
 * @param llm the language model that explains retrieved passages (docs/RAG_ASSISTANT.md)
 * @param secret server secret for keyed hashes and encryption at rest ({@code JANSATHI_SECRET})
 * @param sms how one-time codes reach the phone
 * @param push Web Push reminders; off unless both keys are set
 * @param embed the embedding service for semantic retrieval; off by default
 */
@ConfigurationProperties("jansathi")
public record JansathiProperties(
		String bundleDir, boolean previewFallback, boolean includeDrafts, List<String> corsOrigins, String adminToken, Llm llm, String secret, Sms sms, Push push, Embed embed) {

	public JansathiProperties {
		bundleDir = bundleDir == null || bundleDir.isBlank() ? null : bundleDir;
		corsOrigins = corsOrigins == null ? List.of() : List.copyOf(corsOrigins);
		llm = llm == null ? new Llm(null, null, null, null, null) : llm;
		sms = sms == null ? new Sms(null) : sms;
		push = push == null ? new Push(null, null, null) : push;
		embed = embed == null ? new Embed(null, null, null, null, null) : embed;
	}

	/**
	 * The bundle directory as a filesystem path, or null. Bound as a string: Spring's own Path
	 * conversion goes through resource loading and rejects relative paths such as ../frontend.
	 */
	public Path bundlePath() {
		return bundleDir == null ? null : Path.of(bundleDir);
	}

	/**
	 * @param provider {@code none} (default: keyword retrieval only) or {@code ml-service}
	 * @param baseUrl the internal ML service, e.g. http://ml:8090
	 * @param token shared service token; may be blank on a private network
	 * @param model the model the service runs, kept with cached vectors
	 * @param timeout per request; a slow service falls back to keywords
	 */
	public record Embed(String provider, String baseUrl, String token, String model, Duration timeout) {

		public Embed {
			provider = provider == null || provider.isBlank() ? "none" : provider;
			token = token == null || token.isBlank() ? null : token;
			timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
		}
	}

	/**
	 * @param publicKey VAPID public key, base64url
	 * @param privateKey VAPID private key, base64url
	 * @param subject {@code mailto:} or {@code https:} contact the push services can reach
	 */
	public record Push(String publicKey, String privateKey, String subject) {

		public Push {
			publicKey = publicKey == null || publicKey.isBlank() ? null : publicKey;
			privateKey = privateKey == null || privateKey.isBlank() ? null : privateKey;
			subject = subject == null || subject.isBlank() ? "https://github.com/PritBlitz/JanSathi" : subject;
		}
	}

	/**
	 * @param provider {@code dev} (default): the code is returned in the API response and shown on
	 *     screen, for development and demos only. A real SMS provider is added as another
	 *     {@code SmsSender} implementation.
	 */
	public record Sms(String provider) {

		public Sms {
			provider = provider == null || provider.isBlank() ? "dev" : provider;
		}
	}

	/**
	 * @param provider {@code none} (default: answers are cited passages only) or {@code
	 *     openai-compatible} (Groq, Ollama, Gemini's OpenAI endpoint, …)
	 * @param baseUrl e.g. https://api.groq.com/openai/v1 or http://localhost:11434/v1
	 * @param apiKey bearer key; may be blank for a local Ollama
	 * @param model model id sent to the provider
	 * @param timeout per request; a slow model degrades to passages only, it never blocks an answer
	 */
	public record Llm(String provider, String baseUrl, String apiKey, String model, Duration timeout) {

		public Llm {
			provider = provider == null || provider.isBlank() ? "none" : provider;
			timeout = timeout == null ? Duration.ofSeconds(20) : timeout;
		}
	}
}
