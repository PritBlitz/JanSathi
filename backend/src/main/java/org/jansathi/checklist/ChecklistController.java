package org.jansathi.checklist;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jansathi.content.BundleRepository;
import org.jansathi.content.ContentBundle;
import org.jansathi.content.Languages;
import org.jansathi.content.Localized;
import org.jansathi.web.BadRequestException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * De-duplicated document checklist for the schemes a user picked (brief §6.4). Documents are only
 * listed; whether the user has them stays on the device.
 */
@RestController
@RequestMapping("/api/v1")
public class ChecklistController {

	private static final int MAX_SCHEMES = 50;

	private final BundleRepository bundles;

	public ChecklistController(BundleRepository bundles) {
		this.bundles = bundles;
	}

	public record ChecklistRequest(List<String> schemes, String lang) {}

	/**
	 * @param mandatory true if any of the schemes requires it
	 * @param schemes slugs of the schemes that list this document
	 * @param source where the how-to-obtain text comes from; null if none is recorded
	 */
	public record ChecklistItem(
			String code,
			String icon,
			Localized name,
			boolean mandatory,
			List<String> schemes,
			Localized howTo,
			Localized office,
			String portalUrl,
			JsonNode source) {}

	public record Checklist(String version, boolean preview, List<ChecklistItem> items) {}

	@PostMapping("/checklist")
	public Checklist checklist(@RequestBody ChecklistRequest request) {
		ContentBundle b = bundles.get();
		if (request.schemes() == null || request.schemes().isEmpty() || request.schemes().size() > MAX_SCHEMES) {
			throw new BadRequestException("schemes must list 1 to " + MAX_SCHEMES + " scheme slugs");
		}
		String lang = Languages.orEnglish(request.lang());
		Map<String, Boolean> mandatory = new LinkedHashMap<>();
		Map<String, List<String>> usedBy = new LinkedHashMap<>();
		for (String slug : request.schemes()) {
			JsonNode scheme = b.scheme(slug).orElseThrow(() -> new BadRequestException("Unknown scheme: " + slug));
			for (JsonNode d : scheme.path("documents")) {
				String code = d.path("code").asString();
				mandatory.merge(code, d.path("mandatory").asBoolean(false), Boolean::logicalOr);
				usedBy.computeIfAbsent(code, k -> new ArrayList<>()).add(slug);
			}
		}
		List<ChecklistItem> items = new ArrayList<>();
		for (Map.Entry<String, Boolean> e : mandatory.entrySet()) {
			JsonNode doc = b.document(e.getKey());
			items.add(new ChecklistItem(
					e.getKey(),
					doc.path("icon").asString(""),
					Localized.pick(doc.path("name"), lang),
					e.getValue(),
					usedBy.get(e.getKey()),
					Localized.pick(doc.path("how_to"), lang),
					doc.has("office") ? Localized.pick(doc.get("office"), lang) : null,
					doc.path("portal_url").asString(null),
					doc.has("source") ? doc.get("source") : null));
		}
		// Mandatory documents first; otherwise the order the schemes list them.
		items.sort(Comparator.comparing(ChecklistItem::mandatory).reversed());
		return new Checklist(b.version(), b.preview(), items);
	}
}
