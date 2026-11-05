package org.jansathi.account;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * One POST to a messaging provider, with the JDK's client. Redirects are not followed, and bodies
 * are the caller's: they hold phone numbers, so they are never logged.
 */
public final class ProviderHttp {

	/** Status -1: the provider could not be reached. */
	public record Answer(int status, String body) {

		public boolean ok() {
			return status >= 200 && status < 300;
		}
	}

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();

	private ProviderHttp() {}

	public static Answer post(String url, String contentType, String body, Map<String, String> headers) {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(15))
				.header("Content-Type", contentType)
				.POST(HttpRequest.BodyPublishers.ofString(body));
		headers.forEach(b::header);
		try {
			HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
			return new Answer(r.statusCode(), r.body());
		} catch (IOException e) {
			return new Answer(-1, "");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new Answer(-1, "");
		}
	}

	public static String form(Map<String, String> fields) {
		return fields.entrySet().stream()
				.map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
				.collect(Collectors.joining("&"));
	}
}
