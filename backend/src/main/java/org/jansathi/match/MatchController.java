package org.jansathi.match;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jansathi.content.BundleRepository;
import org.jansathi.content.ContentBundle;
import org.jansathi.rules.MatchResponse;
import org.jansathi.rules.RulesEngine;
import org.jansathi.web.BadRequestException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import tools.jackson.databind.JsonNode;

/**
 * Eligibility matching (docs/RULES_ENGINE.md §9.7). The PWA normally matches on the device with
 * the bundle from {@code GET /rules/bundle}; {@code POST /match} gives the same answer for clients
 * that can't run JavaScript. The profile is used for this request only and never stored or logged.
 */
@RestController
@RequestMapping("/api/v1")
public class MatchController {

	private static final java.time.Duration BUNDLE_MAX_AGE = java.time.Duration.ofMinutes(5);

	private final BundleRepository bundles;
	private final RulesEngine engine;

	public MatchController(BundleRepository bundles, RulesEngine engine) {
		this.bundles = bundles;
		this.engine = engine;
	}

	/**
	 * @param profile flat {@code field → value} object; only fields from fields.json are accepted
	 * @param skipped fields the user chose to skip (only excluded from {@code next_question})
	 * @param asOf date used for the staleness check; today when absent
	 * @param schemes slugs to match; all schemes when absent
	 */
	public record MatchRequest(JsonNode profile, List<String> skipped, LocalDate asOf, List<String> schemes) {}

	@PostMapping("/match")
	public MatchResponse match(@RequestBody MatchRequest request) {
		ContentBundle b = bundles.get();
		JsonNode profile = request.profile();
		if (profile == null || !profile.isObject()) {
			throw new BadRequestException("profile must be a JSON object");
		}
		for (Map.Entry<String, JsonNode> e : profile.properties()) {
			if (!b.fields().isKnown(e.getKey())) {
				// Data minimisation: the API accepts only the fields the rules can use.
				throw new BadRequestException("Unknown profile field: " + e.getKey());
			}
		}
		String state = profile.path("state").isString() ? profile.get("state").asString() : null;
		List<JsonNode> schemes = b.schemesFor(state);
		if (request.schemes() != null) {
			Set<String> wanted = new HashSet<>(request.schemes());
			schemes = schemes.stream().filter(s -> wanted.contains(s.path("slug").asString())).toList();
		}
		Set<String> skipped = request.skipped() == null ? Set.of() : new HashSet<>(request.skipped());
		LocalDate asOf = request.asOf() == null ? LocalDate.now() : request.asOf();
		return engine.match(b.version(), schemes, profile, b.fields(), skipped, asOf);
	}

	/** The rule bundle for on-device matching, identical to the PWA's static bundle file. */
	@GetMapping("/rules/bundle")
	public ResponseEntity<JsonNode> bundle(WebRequest request) {
		ContentBundle b = bundles.get();
		// Weak ETag: Tomcat does not gzip responses that carry a strong one.
		String etag = "W/\"" + b.version() + "\"";
		if (request.checkNotModified(etag)) {
			return null;
		}
		return ResponseEntity.ok()
				.eTag(etag)
				.cacheControl(CacheControl.maxAge(BUNDLE_MAX_AGE).cachePublic())
				.header("X-JanSathi-Preview", String.valueOf(b.preview()))
				.body(b.raw());
	}
}
