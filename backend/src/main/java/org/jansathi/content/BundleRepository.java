package org.jansathi.content;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jansathi.JansathiProperties;
import org.jansathi.rules.FieldCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Loads the published rule bundle written by {@code tools/build-bundle.mjs}. The API serves the
 * same files as the static PWA, so on-device and server matching always use identical rules.
 *
 * <p>Until a reviewer publishes a scheme, bundle.json is empty. With {@code
 * jansathi.preview-fallback} the draft bundle (bundle.preview.json) is served instead and every
 * response says so, mirroring the PWA's preview banner.
 */
@Repository
public class BundleRepository {

	private static final Logger log = LoggerFactory.getLogger(BundleRepository.class);

	private final JansathiProperties properties;
	private final ObjectMapper json;
	private volatile ContentBundle current;

	public BundleRepository(JansathiProperties properties, ObjectMapper json) {
		this.properties = properties;
		this.json = json;
		reload();
	}

	public ContentBundle get() {
		return current;
	}

	/** Re-reads the bundle files; call after {@code npm run build} when serving from a directory. */
	public synchronized ContentBundle reload() {
		ContentBundle published = properties.includeDrafts() ? null : load("", false);
		ContentBundle chosen = published;
		if (published == null || (published.centralSchemes().isEmpty() && published.stateSchemes().isEmpty() && properties.previewFallback())) {
			chosen = load(".preview", true);
		}
		current = chosen;
		log.info("Loaded rule bundle {} ({} central schemes, {} states{})",
				chosen.version(), chosen.centralSchemes().size(), chosen.stateSchemes().size(), chosen.preview() ? ", preview" : "");
		return chosen;
	}

	private ContentBundle load(String suffix, boolean preview) {
		JsonNode raw = read("bundle" + suffix + ".json");
		List<JsonNode> central = new ArrayList<>();
		raw.path("schemes").forEach(central::add);
		Map<String, List<JsonNode>> states = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> e : raw.path("states").properties()) {
			List<JsonNode> list = new ArrayList<>();
			read("states/" + e.getKey() + suffix + ".json").path("schemes").forEach(list::add);
			states.put(e.getKey(), list);
		}
		JsonNode fieldsJson = raw.path("fields");
		return new ContentBundle(
				raw.path("version").asString(""),
				preview,
				raw,
				fieldsJson,
				new FieldCatalog(fieldsJson),
				raw.path("documents"),
				central,
				states);
	}

	private JsonNode read(String name) {
		try {
			if (properties.bundleDir() != null) {
				return json.readTree(Files.readString(properties.bundleDir().resolve(name)));
			}
			try (InputStream in = new ClassPathResource("bundle/" + name).getInputStream()) {
				return json.readTree(in);
			}
		} catch (IOException e) {
			throw new IllegalStateException("Rule bundle file " + name + " cannot be read (run npm run build)", e);
		}
	}

}
