package org.jansathi.speech;

import java.util.List;

/**
 * Server speech (docs/I18N_AND_VOICE.md §5.3), the fallback for devices with no browser voice or
 * recogniser. Off by default. Implementations must not store or log text or audio.
 */
public interface SpeechProvider {

	/** The languages each direction can serve now; both empty when speech is off or unreachable. */
	record Languages(List<String> tts, List<String> stt) {

		public static final Languages NONE = new Languages(List.of(), List.of());
	}

	boolean enabled();

	Languages languages();

	/** @return a WAV file */
	byte[] synthesize(String text, String lang);

	/** @param audio a recording in any common container (WAV, WebM, Ogg, MP4) */
	String transcribe(byte[] audio, String lang);

	/** The service is down or too slow. */
	class SpeechUnavailableException extends RuntimeException {
		public SpeechUnavailableException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	/** The service refused this input, e.g. audio it could not decode. */
	class SpeechRejectedException extends RuntimeException {
		public SpeechRejectedException(String message) {
			super(message);
		}
	}

	SpeechProvider DISABLED = new SpeechProvider() {
		@Override
		public boolean enabled() {
			return false;
		}

		@Override
		public Languages languages() {
			return Languages.NONE;
		}

		@Override
		public byte[] synthesize(String text, String lang) {
			throw new SpeechUnavailableException("No speech provider is configured", null);
		}

		@Override
		public String transcribe(byte[] audio, String lang) {
			throw new SpeechUnavailableException("No speech provider is configured", null);
		}
	};
}
