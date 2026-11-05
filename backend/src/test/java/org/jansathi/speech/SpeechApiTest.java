package org.jansathi.speech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** The speech API with a fake provider: no sign-in needed, limits hold, failures are clean. */
@SpringBootTest
@AutoConfigureMockMvc
class SpeechApiTest {

	private static final byte[] WAV = {'R', 'I', 'F', 'F', 0, 0, 0, 0};

	static class FakeSpeech implements SpeechProvider {
		volatile boolean on = true;
		volatile RuntimeException failure;
		volatile CountDownLatch hold;
		final CountDownLatch entered = new CountDownLatch(1);
		final AtomicReference<String> lastLang = new AtomicReference<>();

		@Override
		public boolean enabled() {
			return on;
		}

		@Override
		public Languages languages() {
			return on ? new Languages(List.of("hi", "en"), List.of("hi")) : Languages.NONE;
		}

		@Override
		public byte[] synthesize(String text, String lang) {
			lastLang.set(lang);
			if (failure != null) {
				throw failure;
			}
			return WAV;
		}

		@Override
		public String transcribe(byte[] audio, String lang) {
			lastLang.set(lang);
			if (failure != null) {
				throw failure;
			}
			if (hold != null) {
				entered.countDown();
				try {
					hold.await();
				} catch (InterruptedException e) {
					throw new IllegalStateException(e);
				}
			}
			return "नमस्ते " + audio.length;
		}
	}

	@TestConfiguration
	static class Fake {
		@Bean
		@Primary
		FakeSpeech fakeSpeech() {
			return new FakeSpeech();
		}
	}

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> "jdbc:h2:mem:speechtest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
		registry.add("jansathi.speech.max-text-chars", () -> "20");
		registry.add("jansathi.speech.max-audio-bytes", () -> "16");
		registry.add("jansathi.speech.per-minute", () -> "6");
		registry.add("jansathi.speech.max-concurrent", () -> "1");
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	FakeSpeech fake;

	private static int address;

	/** Each test comes from its own address, so the per-minute limit does not carry over. */
	private String next;

	@BeforeEach
	void reset() {
		fake.on = true;
		fake.failure = null;
		fake.hold = null;
		next = "10.0.0." + (++address);
	}

	private org.springframework.test.web.servlet.ResultActions tts(String json) throws Exception {
		return mvc.perform(post("/api/v1/speech/tts").with(r -> {
			r.setRemoteAddr(next);
			return r;
		}).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private org.springframework.test.web.servlet.ResultActions stt(String lang, byte[] audio, String type) throws Exception {
		var request = post("/api/v1/speech/stt").with(r -> {
			r.setRemoteAddr(next);
			return r;
		}).param("lang", lang).content(audio);
		return mvc.perform(type == null ? request : request.contentType(type));
	}

	@Test
	void statusListsTheLanguagesOfEachDirection() throws Exception {
		mvc.perform(get("/api/v1/speech/status"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tts[0]").value("hi"))
				.andExpect(jsonPath("$.stt.length()").value(1));
	}

	@Test
	void whenSpeechIsOffStatusIsEmptyAndRequestsAre503() throws Exception {
		fake.on = false;
		mvc.perform(get("/api/v1/speech/status"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tts.length()").value(0))
				.andExpect(jsonPath("$.stt.length()").value(0));
		tts("{\"text\":\"नमस्ते\",\"lang\":\"hi\"}").andExpect(status().isServiceUnavailable());
	}

	@Test
	void ttsNeedsNoSignInAndReturnsWav() throws Exception {
		tts("{\"text\":\"नमस्ते\",\"lang\":\"hi\"}")
				.andExpect(status().isOk())
				.andExpect(content().contentType("audio/wav"))
				.andExpect(content().bytes(WAV))
				.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
		assertThat(fake.lastLang.get()).isEqualTo("hi");
	}

	@Test
	void ttsRefusesEmptyTextUnknownLanguagesAndLongText() throws Exception {
		tts("{\"text\":\" \",\"lang\":\"hi\"}").andExpect(status().isBadRequest());
		tts("{\"text\":\"a\",\"lang\":\"fr\"}").andExpect(status().isBadRequest());
		tts("{\"text\":\"123456789012345678901\",\"lang\":\"hi\"}").andExpect(status().isPayloadTooLarge());
	}

	@Test
	void sttReturnsTheTextAsJson() throws Exception {
		stt("hi", new byte[] {1, 2, 3}, "audio/webm")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.text").value("नमस्ते 3"));
	}

	@Test
	void sttRefusesWhatIsNotAudio() throws Exception {
		stt("hi", new byte[0], "audio/wav").andExpect(status().isBadRequest());
		stt("xx", new byte[] {1}, "audio/wav").andExpect(status().isBadRequest());
		stt("hi", new byte[] {1}, "text/plain").andExpect(status().isUnsupportedMediaType());
		stt("hi", new byte[] {1}, null).andExpect(status().isUnsupportedMediaType());
		stt("hi", new byte[17], "audio/wav").andExpect(status().isPayloadTooLarge());
	}

	@Test
	void theLimitIsPerAddressAndCoversBothDirections() throws Exception {
		for (int i = 0; i < 3; i++) {
			tts("{\"text\":\"a\",\"lang\":\"en\"}").andExpect(status().isOk());
			stt("hi", new byte[] {1}, "audio/wav").andExpect(status().isOk());
		}
		tts("{\"text\":\"a\",\"lang\":\"en\"}").andExpect(status().isTooManyRequests());
		next = "10.1.1.1";
		tts("{\"text\":\"a\",\"lang\":\"en\"}").andExpect(status().isOk());
	}

	@Test
	void whenAllSlotsAreBusyTheAnswerIs503NotAQueue() throws Exception {
		fake.hold = new CountDownLatch(1);
		String first = next;
		Thread busy = new Thread(() -> {
			try {
				next = first;
				stt("hi", new byte[] {1}, "audio/wav");
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
		busy.start();
		fake.entered.await();
		next = "10.2.2.2";
		tts("{\"text\":\"a\",\"lang\":\"en\"}").andExpect(status().isServiceUnavailable());
		fake.hold.countDown();
		busy.join();
	}

	@Test
	void providerFailuresBecomeCleanProblems() throws Exception {
		fake.failure = new SpeechProvider.SpeechUnavailableException("down: secret detail", null);
		tts("{\"text\":\"a\",\"lang\":\"en\"}")
				.andExpect(status().isServiceUnavailable())
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
		fake.failure = new SpeechProvider.SpeechRejectedException("The speech service could not read the audio");
		stt("hi", new byte[] {1}, "audio/wav").andExpect(status().isBadRequest());
	}
}
