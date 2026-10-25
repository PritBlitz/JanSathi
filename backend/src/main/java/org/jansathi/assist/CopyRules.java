package org.jansathi.assist;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Phrases that promise eligibility or approval, in all 10 languages. Loaded from
 * {@code tools/banned-phrases.json} (packaged as {@code copy/banned-phrases.json}), the same file
 * the copy lint uses, so assistant answers obey exactly the rules the UI copy does.
 */
@Component
public class CopyRules {

	private final List<Pattern> banned = new ArrayList<>();
	private final List<Pattern> allow = new ArrayList<>();

	public CopyRules(ObjectMapper json) {
		try (InputStream in = new ClassPathResource("copy/banned-phrases.json").getInputStream()) {
			JsonNode root = json.readTree(in);
			root.path("banned").properties().forEach(e -> e.getValue().forEach(p -> banned.add(compile(p))));
			root.path("allow").forEach(p -> allow.add(compile(p)));
		} catch (IOException e) {
			throw new UncheckedIOException("copy/banned-phrases.json is missing from the classpath", e);
		}
	}

	private static Pattern compile(JsonNode p) {
		int flags = Pattern.UNICODE_CHARACTER_CLASS;
		if (p.path("flags").asString("").contains("i")) {
			flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
		}
		return Pattern.compile(p.path("pattern").asString(), flags);
	}

	/** The banned patterns found in text, after removing allowed official names. Empty when clean. */
	public List<String> hits(String text) {
		String t = Normalizer.normalize(text, Normalizer.Form.NFC).replaceAll("[\\u200C\\u200D]", "");
		for (Pattern a : allow) {
			t = a.matcher(t).replaceAll(" ");
		}
		List<String> out = new ArrayList<>();
		for (Pattern b : banned) {
			if (b.matcher(t).find()) {
				out.add(b.pattern());
			}
		}
		return out;
	}
}
