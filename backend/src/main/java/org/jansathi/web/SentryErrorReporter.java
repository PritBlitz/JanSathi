package org.jansathi.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Sends errors to anything that speaks Sentry's envelope protocol (Sentry, self-hosted Sentry,
 * GlitchTip), from a DSN, with the JDK's HTTP client. What leaves the server: the exception types,
 * scrubbed messages, stack frames, the route pattern and the environment. Never a request body,
 * header, query string, user or address.
 */
public class SentryErrorReporter implements ErrorReporter {

	private static final Logger log = LoggerFactory.getLogger(SentryErrorReporter.class);
	private static final int MAX_FRAMES = 40;

	private final URI envelope;
	private final String dsn;
	private final String auth;
	private final String environment;
	private final ObjectMapper json;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

	public SentryErrorReporter(String dsn, String environment, ObjectMapper json) {
		URI u = URI.create(dsn);
		String path = u.getPath();
		int slash = path.lastIndexOf('/');
		if (u.getUserInfo() == null || slash < 0 || slash == path.length() - 1) {
			throw new IllegalStateException("JANSATHI_SENTRY_DSN is not a DSN (https://key@host/project)");
		}
		String key = u.getUserInfo().split(":")[0];
		this.envelope = URI.create(u.getScheme() + "://" + u.getHost() + (u.getPort() < 0 ? "" : ":" + u.getPort())
				+ path.substring(0, slash) + "/api/" + path.substring(slash + 1) + "/envelope/");
		this.dsn = dsn;
		this.auth = "Sentry sentry_version=7, sentry_client=jansathi/1, sentry_key=" + key;
		this.environment = environment;
		this.json = json;
	}

	String body(Throwable error, String where, String eventId, Instant now) {
		List<Map<String, Object>> values = new ArrayList<>();
		for (Throwable t = error; t != null && values.size() < 5; t = t.getCause() == t ? null : t.getCause()) {
			List<Map<String, Object>> frames = new ArrayList<>();
			Arrays.stream(t.getStackTrace()).limit(MAX_FRAMES).forEach(f -> {
				Map<String, Object> frame = new LinkedHashMap<>();
				frame.put("module", f.getClassName());
				frame.put("function", f.getMethodName());
				frame.put("lineno", f.getLineNumber());
				frame.put("in_app", f.getClassName().startsWith("org.jansathi"));
				frames.addFirst(frame);
			});
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("type", t.getClass().getName());
			value.put("value", Scrub.clean(t.getMessage()));
			value.put("stacktrace", Map.of("frames", frames));
			values.addFirst(value);
		}
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("event_id", eventId);
		event.put("timestamp", now.toString());
		event.put("platform", "java");
		event.put("level", "error");
		event.put("environment", environment);
		event.put("transaction", where);
		event.put("exception", Map.of("values", values));
		return json.writeValueAsString(Map.of("event_id", eventId, "dsn", dsn, "sent_at", now.toString())) + "\n"
				+ json.writeValueAsString(Map.of("type", "event")) + "\n" + json.writeValueAsString(event) + "\n";
	}

	@Override
	public void report(Throwable error, String where) {
		String id = UUID.randomUUID().toString().replace("-", "");
		String body = body(error, where, id, Instant.now());
		// Off the request thread: an error report must not make the error worse.
		Thread.startVirtualThread(() -> {
			HttpRequest request = HttpRequest.newBuilder(envelope)
					.timeout(Duration.ofSeconds(10))
					.header("Content-Type", "application/x-sentry-envelope")
					.header("X-Sentry-Auth", auth)
					.POST(HttpRequest.BodyPublishers.ofString(body))
					.build();
			try {
				int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
				if (status >= 300) {
					log.warn("Error reporting answered {}", status);
				}
			} catch (java.io.IOException e) {
				log.warn("Error report failed: {}", e.getClass().getSimpleName());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
	}
}
