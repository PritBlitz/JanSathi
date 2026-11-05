package org.jansathi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

/** Rate limits by the real client address, and what makes a forwarded header believable. */
@SpringBootTest
@AutoConfigureMockMvc
class RateLimitTest {

	private static final String PROXY = "172.18.0.5";

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> "jdbc:h2:mem:ratelimit;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
		registry.add("jansathi.web.rate-limit-enabled", () -> "true");
		registry.add("jansathi.web.trusted-proxies", () -> "172.16.0.0/12,::1");
	}

	@Autowired
	MockMvc mvc;

	private static RequestPostProcessor from(String peer) {
		return r -> {
			r.setRemoteAddr(peer);
			return r;
		};
	}

	private static String client(String peer, String forwarded) {
		MockHttpServletRequest r = new MockHttpServletRequest();
		r.setRemoteAddr(peer);
		if (forwarded != null) {
			r.addHeader("X-Forwarded-For", forwarded);
		}
		return new ClientAddress(new WebProperties(List.of("172.16.0.0/12", "10.1.1.1", "2001:db8::/32"), true, null, null, null)).of(r);
	}

	@Test
	void aForwardedHeaderCountsOnlyFromOurProxy() {
		assertThat(client("203.0.113.9", "1.2.3.4")).isEqualTo("203.0.113.9");
		assertThat(client(PROXY, "1.2.3.4")).isEqualTo("1.2.3.4");
		assertThat(client(PROXY, null)).isEqualTo(PROXY);
		assertThat(client("2001:db8::7", "1.2.3.4")).isEqualTo("1.2.3.4");
	}

	@Test
	void aClientCannotForgeItsWayPastTheProxy() {
		// The proxy appends what it saw, so the forged entry is on the left.
		assertThat(client(PROXY, "9.9.9.9, 1.2.3.4")).isEqualTo("1.2.3.4");
		assertThat(client(PROXY, "9.9.9.9, 1.2.3.4, 10.1.1.1")).isEqualTo("1.2.3.4");
		// Rubbish is not an address, and is never looked up.
		assertThat(client(PROXY, "evil.example")).isEqualTo(PROXY);
		assertThat(client(PROXY, "1.2.3.4, evil.example")).isEqualTo(PROXY);
	}

	@Test
	void aBadProxySettingStopsTheStart() {
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ClientAddress(new WebProperties(List.of("proxy.example"), true, null, null, null)))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void writesAreLimitedPerAddress() throws Exception {
		String report = "{\"scheme\": \"pm-kisan\", \"reason\": \"outdated\"}";
		// Window edges would make a tight count flaky, so go well past the limit and count the refusals.
		int refused = 0;
		for (int i = 0; i < 12; i++) {
			var r = mvc.perform(post("/api/v1/reports").contentType(MediaType.APPLICATION_JSON).content(report).with(from("198.51.100.1"))).andReturn().getResponse();
			if (r.getStatus() == 429) {
				refused++;
				assertThat(r.getHeader("Retry-After")).isNotBlank();
				assertThat(r.getHeader("Content-Security-Policy")).isNotBlank();
			}
		}
		assertThat(refused).isGreaterThan(0);
		// Someone else is not affected, and neither are reads.
		mvc.perform(post("/api/v1/reports").contentType(MediaType.APPLICATION_JSON).content(report).with(from("198.51.100.2"))).andExpect(status().isCreated());
		mvc.perform(get("/api/v1/categories").with(from("198.51.100.1"))).andExpect(status().isOk());
	}

	@Test
	void behindTheProxyEachClientHasItsOwnLimit() throws Exception {
		String report = "{\"scheme\": \"pm-kisan\", \"reason\": \"outdated\"}";
		for (int i = 0; i < 12; i++) {
			mvc.perform(post("/api/v1/reports").contentType(MediaType.APPLICATION_JSON).content(report).with(from(PROXY)).header("X-Forwarded-For", "198.51.100.3"));
		}
		mvc.perform(post("/api/v1/reports").contentType(MediaType.APPLICATION_JSON).content(report).with(from(PROXY)).header("X-Forwarded-For", "198.51.100.4"))
				.andExpect(status().isCreated());
		// Forging another address on the left changes nothing.
		mvc.perform(post("/api/v1/reports").contentType(MediaType.APPLICATION_JSON).content(report).with(from(PROXY)).header("X-Forwarded-For", "8.8.8.8, 198.51.100.3"))
				.andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
	}

	@Test
	void errorReportsAreScrubbed() {
		var reporter = new SentryErrorReporter("https://key@sentry.example/42", "production", JsonMapper.builder().build());
		Exception e = new IllegalStateException("Failed for 9876543210 and a@b.org with Bearer abc.def and Aadhaar 2345 6789 0123",
				new RuntimeException("caused by 123456789012"));
		String body = reporter.body(e, "POST /api/v1/ask", "0123456789abcdef0123456789abcdef", Instant.parse("2026-11-05T00:00:00Z"));

		assertThat(body).contains("[number removed]").contains("[email removed]").contains("[token removed]")
				.doesNotContain("9876543210").doesNotContain("a@b.org").doesNotContain("abc.def").doesNotContain("2345 6789").doesNotContain("123456789012");
		assertThat(body).contains("IllegalStateException").contains("POST /api/v1/ask").contains("\"environment\":\"production\"");
		assertThat(body.split("\n")).hasSize(3);
	}

	@Test
	void aBadDsnStopsTheStart() {
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> new SentryErrorReporter("https://sentry.example/42", "x", JsonMapper.builder().build()))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void structuredLogsAreScrubbed() {
		var writer = org.springframework.boot.json.JsonWriter.<String>of(m -> {
			m.add("message", s -> s);
			new LogScrubber().customize((org.springframework.boot.json.JsonWriter.Members<Object>) (Object) m);
		});
		assertThat(writer.writeToString("call 9876543210")).contains("[number removed]").doesNotContain("9876543210");
	}
}
