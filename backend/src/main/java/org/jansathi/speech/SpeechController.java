package org.jansathi.speech;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.util.function.Supplier;
import org.jansathi.content.Languages;
import org.jansathi.web.BadRequestException;
import org.jansathi.web.ClientAddress;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Server speech for anyone, signed in or not (docs/I18N_AND_VOICE.md §5.3). Text and audio pass
 * through memory to the ML service and back; nothing here stores or logs them.
 */
@RestController
@RequestMapping("/api/v1/speech")
public class SpeechController {

	private static final MediaType WAV = MediaType.parseMediaType("audio/wav");

	private final SpeechProvider speech;
	private final SpeechProperties settings;
	private final SpeechLimiter limiter;
	private final ClientAddress addresses;

	public SpeechController(SpeechProvider speech, SpeechProperties settings, SpeechLimiter limiter, ClientAddress addresses) {
		this.speech = speech;
		this.settings = settings;
		this.limiter = limiter;
		this.addresses = addresses;
	}

	public record TtsRequest(@NotBlank String text, @NotBlank String lang) {}

	public record Transcript(String text) {}

	@GetMapping("/status")
	public ResponseEntity<SpeechProvider.Languages> status() {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(speech.languages());
	}

	@PostMapping("/tts")
	public ResponseEntity<byte[]> tts(@Valid @RequestBody TtsRequest body, HttpServletRequest request) {
		String lang = language(body.lang());
		if (body.text().length() > settings.maxTextChars()) {
			throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Text is too long to read aloud");
		}
		byte[] wav = limited(request, () -> speech.synthesize(body.text(), lang));
		return ResponseEntity.ok().contentType(WAV).cacheControl(CacheControl.noStore()).body(wav);
	}

	@PostMapping("/stt")
	public ResponseEntity<Transcript> stt(@RequestParam String lang, HttpServletRequest request) throws IOException {
		String code = language(lang);
		String type = request.getContentType();
		if (type == null || !(type.startsWith("audio/") || type.startsWith(MediaType.APPLICATION_OCTET_STREAM_VALUE))) {
			throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Send the recording as audio/*");
		}
		if (request.getContentLengthLong() > settings.maxAudioBytes()) {
			throw tooLarge();
		}
		// Chunked uploads have no length: read one byte past the limit to notice them.
		byte[] audio = request.getInputStream().readNBytes(settings.maxAudioBytes() + 1);
		if (audio.length > settings.maxAudioBytes()) {
			throw tooLarge();
		}
		if (audio.length == 0) {
			throw new BadRequestException("No audio was sent");
		}
		String text = limited(request, () -> speech.transcribe(audio, code));
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Transcript(text));
	}

	private static ResponseStatusException tooLarge() {
		return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "The recording is too large");
	}

	private static String language(String lang) {
		if (!Languages.CODES.contains(lang)) {
			throw new BadRequestException("Unknown language");
		}
		return lang;
	}

	/** Answers 503 when off or down, so the app can fall back to browser speech or typing. */
	private <T> T limited(HttpServletRequest request, Supplier<T> work) {
		if (!speech.enabled()) {
			throw unavailable();
		}
		if (!limiter.allow(addresses.of(request))) {
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many speech requests. Wait a minute.");
		}
		if (!limiter.tryStart()) {
			throw unavailable();
		}
		try {
			return work.get();
		} catch (SpeechProvider.SpeechRejectedException e) {
			throw new BadRequestException(e.getMessage());
		} catch (SpeechProvider.SpeechUnavailableException e) {
			throw unavailable();
		} finally {
			limiter.finish();
		}
	}

	private static ResponseStatusException unavailable() {
		return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Server speech is not available");
	}
}
