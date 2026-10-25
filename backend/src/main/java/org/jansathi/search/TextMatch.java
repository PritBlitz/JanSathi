package org.jansathi.search;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Text normalisation shared by search and the assistant's retrieval. Mirrors the PWA's search. */
public final class TextMatch {

	// Keeps combining marks (\p{M}): Indic vowel signs are marks, not letters.
	private static final Pattern NOT_WORD = Pattern.compile("[^\\p{L}\\p{M}\\p{N}\\s]", Pattern.UNICODE_CHARACTER_CLASS);
	private static final Pattern SPACES = Pattern.compile("\\s+");

	/**
	 * Function words that would otherwise match almost every passage: English and Hindi (the MVP
	 * assistant languages, docs/RAG_ASSISTANT.md). Other languages are left to inverse document
	 * frequency until a native speaker adds theirs.
	 */
	private static final Set<String> STOP = Set.of(
			"the", "and", "for", "what", "which", "who", "how", "can", "get", "are", "is", "do", "does", "with", "from",
			"this", "that", "about", "scheme", "schemes", "yojana", "my", "me", "you", "your", "any", "there", "under", "of", "to", "in", "a",
			"i", "am", "be", "if", "it", "or", "an", "on", "will", "much", "many", "give", "gives", "has", "have", "tell", "please",
			"है", "हैं", "था", "थी", "के", "की", "का", "को", "में", "से", "लिए", "पर", "और", "या", "भी", "क्या", "कौन", "कौनसी", "कैसे",
			"कितना", "कितनी", "कितने", "कब", "कहाँ", "मेरा", "मेरी", "मेरे", "मुझे", "मैं", "हम", "यह", "वह", "ये", "वो", "योजना",
			"होना", "होनी", "होता", "होती", "चाहिए", "सकता", "सकती", "सकते", "कोई", "किस", "किसे", "किसको", "जो", "तो", "ही", "आज");

	private TextMatch() {}

	public static String normalise(String s) {
		String n = Normalizer.normalize(String.valueOf(s).toLowerCase(Locale.ROOT), Normalizer.Form.NFC);
		return SPACES.matcher(NOT_WORD.matcher(n).replaceAll(" ")).replaceAll(" ").trim();
	}

	/** Query words longer than one character, without duplicates. */
	public static List<String> words(String query) {
		return Arrays.stream(normalise(query).split(" ")).filter(w -> w.length() > 1).distinct().toList();
	}

	/** Query words without English function words, for ranking passages. */
	public static List<String> keywords(String query) {
		return words(query).stream().filter(w -> !STOP.contains(w)).toList();
	}

	/** The distinct words of already-normalised text. */
	public static Set<String> tokens(String normalised) {
		return new HashSet<>(Arrays.asList(normalised.split(" ")));
	}

	/**
	 * Whole-word match, so "sc" does not match "scholarship" and कल does not match कल्याण. English
	 * plurals fold to the singular in both directions (widows ~ widow, schemes ~ scheme).
	 */
	public static boolean matches(String word, Set<String> tokens) {
		if (tokens.contains(word) || tokens.contains(word + "s") || tokens.contains(word + "es")) {
			return true;
		}
		if (word.length() > 3 && word.endsWith("es") && tokens.contains(word.substring(0, word.length() - 2))) {
			return true;
		}
		return word.length() > 3 && word.endsWith("s") && tokens.contains(word.substring(0, word.length() - 1));
	}
}
