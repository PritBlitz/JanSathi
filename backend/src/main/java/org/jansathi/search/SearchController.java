package org.jansathi.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jansathi.content.ContentBundle;
import org.jansathi.content.Languages;
import org.jansathi.content.Localized;
import org.jansathi.scheme.SchemeService;
import org.jansathi.scheme.SchemeSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Keyword search over scheme names and descriptions, in English and the user's language. */
@RestController
@RequestMapping("/api/v1")
public class SearchController {

	private static final int MAX_QUERY = 200;

	private final SchemeService schemes;

	public SearchController(SchemeService schemes) {
		this.schemes = schemes;
	}

	public record SearchResults(String version, boolean preview, String query, List<SchemeSummary> schemes) {}

	private record Hit(JsonNode scheme, int score) {}

	@GetMapping("/search")
	public SearchResults search(
			@RequestParam String q, @RequestParam(required = false) String state, @RequestParam(required = false) String lang) {
		String query = q.length() > MAX_QUERY ? q.substring(0, MAX_QUERY) : q;
		String l = Languages.orEnglish(lang);
		ContentBundle b = schemes.bundle();
		List<String> words = TextMatch.words(query);
		List<Hit> hits = new ArrayList<>();
		for (JsonNode s : b.schemesFor(state)) {
			StringBuilder hay = new StringBuilder();
			for (String code : List.of("en", l)) {
				s.path("content").path(code).properties().forEach(e -> hay.append(' ').append(e.getValue().asString("")));
			}
			hay.append(' ').append(Localized.pick(s.path("ministry"), l).text())
					.append(' ').append(s.path("category").asString(""))
					.append(' ').append(s.path("slug").asString(""));
			String text = TextMatch.normalise(hay.toString());
			int score = (int) words.stream().filter(text::contains).count();
			if (score > 0) {
				hits.add(new Hit(s, score));
			}
		}
		hits.sort(Comparator.comparingInt(Hit::score).reversed());
		return new SearchResults(b.version(), b.preview(), query, hits.stream().map(h -> schemes.summarise(h.scheme(), l)).toList());
	}
}
