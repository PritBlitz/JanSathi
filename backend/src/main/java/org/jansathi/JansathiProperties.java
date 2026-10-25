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
 * @param corsOrigins browser origins allowed to call the API (the frontend dev server, the PWA host)
 * @param adminToken shared bearer token for {@code /api/v1/admin/**}; blank switches the admin API off
 * @param llm the language model that explains retrieved passages (docs/RAG_ASSISTANT.md)
 */
@ConfigurationProperties("jansathi")
public record JansathiProperties(Path bundleDir, boolean previewFallback, List<String> corsOrigins, String adminToken, Llm llm) {

	public JansathiProperties {
		bundleDir = bundleDir == null || bundleDir.toString().isBlank() ? null : bundleDir;
		corsOrigins = corsOrigins == null ? List.of() : List.copyOf(corsOrigins);
		llm = llm == null ? new Llm(null, null, null, null, null) : llm;
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
