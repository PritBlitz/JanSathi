package org.jansathi.scheme;

import java.util.List;
import java.util.Map;
import org.jansathi.content.ContentBundle;
import org.jansathi.content.Languages;
import org.jansathi.rules.Confidence;
import org.jansathi.rules.SchemeResult;
import org.jansathi.web.NotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Read API for schemes (docs/ARCHITECTURE.md §5). A scheme is always served with its sources. */
@RestController
@RequestMapping("/api/v1")
public class SchemeController {

	private final SchemeService schemes;

	public SchemeController(SchemeService schemes) {
		this.schemes = schemes;
	}

	public record SchemeList(String version, boolean preview, List<SchemeSummary> schemes) {}

	public record SchemeDetail(String version, boolean preview, Confidence label, boolean stale, JsonNode scheme) {}

	public record Categories(String version, Map<String, Integer> categories) {}

	@GetMapping("/schemes")
	public SchemeList list(
			@RequestParam(required = false) String category,
			@RequestParam(required = false) String state,
			@RequestParam(required = false) String lang) {
		ContentBundle b = schemes.bundle();
		return new SchemeList(b.version(), b.preview(), schemes.list(category, state, Languages.orEnglish(lang)));
	}

	@GetMapping("/schemes/{slug}")
	public SchemeDetail detail(@PathVariable String slug) {
		ContentBundle b = schemes.bundle();
		JsonNode scheme = b.scheme(slug).orElseThrow(() -> new NotFoundException("No scheme with slug " + slug));
		SchemeResult trust = schemes.trust(scheme);
		return new SchemeDetail(b.version(), b.preview(), trust.label(), trust.stale(), scheme);
	}

	@GetMapping("/categories")
	public Categories categories(@RequestParam(required = false) String state) {
		return new Categories(schemes.bundle().version(), schemes.categories(state));
	}
}
