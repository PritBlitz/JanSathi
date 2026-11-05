package org.jansathi.admin;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.jansathi.content.BundleRepository;
import org.jansathi.content.ContentBundle;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Read-only: each scheme's review state and what the daily re-check found about its sources
 * (content/source-status.json, packaged at build time). Nothing can be changed or published here:
 * content is code, and publishing stays in tools/publish.mjs after two independent reviews.
 */
@RestController
@RequestMapping("/api/v1/admin/sources")
public class SourceStatusController {

	/**
	 * @param checkStatus {@code ok} when the re-check has nothing to say, otherwise its status
	 *     ({@code changed}, {@code dismissed}, {@code blocked}, ...)
	 */
	public record Source(String id, String title, String url, String docType, String fetchedAt, String verifiedAt, String checkStatus, String since, String note) {}

	/** @param reverifyDue the day this scheme should be read against its sources again; null if unknown */
	public record Scheme(String slug, String status, String reviewedOn, List<String> reviewers, String reverifyDue, boolean overdue, List<Source> sources) {}

	public record Report(String bundleVersion, boolean preview, List<Scheme> schemes) {}

	private final BundleRepository bundles;
	private final JsonNode status;

	public SourceStatusController(BundleRepository bundles, ObjectMapper json) {
		this.bundles = bundles;
		this.status = load(json);
	}

	private static JsonNode load(ObjectMapper json) {
		ClassPathResource r = new ClassPathResource("content/source-status.json");
		if (!r.exists()) {
			return json.createObjectNode();
		}
		try (InputStream in = r.getInputStream()) {
			return json.readTree(in).path("sources");
		} catch (IOException e) {
			throw new IllegalStateException("content/source-status.json can't be read", e);
		}
	}

	private static String text(JsonNode n) {
		return n.isString() ? n.asString() : null;
	}

	@GetMapping
	public Report report() {
		ContentBundle b = bundles.get();
		LocalDate today = LocalDate.now();
		List<Scheme> schemes = new ArrayList<>();
		for (JsonNode s : b.allBySlug().values()) {
			List<Source> sources = new ArrayList<>();
			String slug = s.path("slug").asString();
			for (JsonNode src : s.path("sources")) {
				String id = src.path("id").asString();
				JsonNode st = status.path(slug + "/" + id);
				sources.add(new Source(id, text(src.path("title")), text(src.path("url")), text(src.path("doc_type")), text(src.path("fetched_at")), text(src.path("verified_at")),
						st.isObject() ? st.path("status").asString("unknown") : "ok", text(st.path("since")), text(st.path("note"))));
			}
			JsonNode v = s.path("verification");
			String reviewedOn = text(v.path("date"));
			List<String> reviewers = new ArrayList<>();
			v.path("reviewers").forEach(r -> reviewers.add(r.asString()));
			LocalDate due = reviewedOn == null || !s.path("reverify_days").isNumber() ? null : LocalDate.parse(reviewedOn).plusDays(s.path("reverify_days").asInt());
			schemes.add(new Scheme(slug, text(s.path("status")), reviewedOn, reviewers, due == null ? null : due.toString(), due != null && due.isBefore(today), sources));
		}
		return new Report(b.version(), b.preview(), schemes);
	}
}
