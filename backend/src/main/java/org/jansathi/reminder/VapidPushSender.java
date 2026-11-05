package org.jansathi.reminder;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPrivateKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Web Push without a payload: an HTTP POST signed with VAPID (RFC 8292, ES256), so only the JDK is
 * needed. With no body there is no RFC 8291 encryption to do.
 */
public class VapidPushSender implements PushSender {

	private static final Logger log = LoggerFactory.getLogger(VapidPushSender.class);
	private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
	private static final Duration TOKEN_LIFETIME = Duration.ofHours(12);
	private static final String TTL_SECONDS = String.valueOf(Duration.ofDays(2).toSeconds());

	private final String publicKey;
	private final PrivateKey privateKey;
	private final String subject;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();

	/**
	 * @param publicKey the uncompressed P-256 point, base64url (what {@code web-push generate-vapid-keys} prints)
	 * @param privateKey the private scalar, base64url
	 * @param subject a {@code mailto:} or {@code https:} contact for the push service
	 */
	public VapidPushSender(String publicKey, String privateKey, String subject) {
		this.publicKey = publicKey;
		this.subject = subject;
		this.privateKey = parsePrivateKey(privateKey);
	}

	private static PrivateKey parsePrivateKey(String base64url) {
		try {
			AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
			params.init(new ECGenParameterSpec("secp256r1"));
			BigInteger d = new BigInteger(1, Base64.getUrlDecoder().decode(base64url));
			return KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(d, params.getParameterSpec(ECParameterSpec.class)));
		} catch (GeneralSecurityException | IllegalArgumentException e) {
			throw new IllegalStateException("JANSATHI_PUSH_PRIVATE_KEY is not a valid VAPID key", e);
		}
	}

	@Override
	public String publicKey() {
		return publicKey;
	}

	/** The value of the Authorization header for one push service. */
	String authorization(String endpoint, Instant now) {
		URI uri = URI.create(endpoint);
		String origin = uri.getScheme() + "://" + uri.getAuthority();
		String header = B64.encodeToString("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
		String claims = B64.encodeToString(("{\"aud\":\"" + origin + "\",\"exp\":" + now.plus(TOKEN_LIFETIME).getEpochSecond() + ",\"sub\":\"" + subject + "\"}")
				.getBytes(StandardCharsets.UTF_8));
		String signing = header + "." + claims;
		try {
			// P1363 is the raw r||s that JWT wants; the default is DER.
			Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
			s.initSign(privateKey);
			s.update(signing.getBytes(StandardCharsets.UTF_8));
			return "vapid t=" + signing + "." + B64.encodeToString(s.sign()) + ", k=" + publicKey;
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("Could not sign a push", e);
		}
	}

	@Override
	public Result send(String endpoint) {
		HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
				.timeout(Duration.ofSeconds(20))
				.header("Authorization", authorization(endpoint, Instant.now()))
				.header("TTL", TTL_SECONDS)
				.header("Urgency", "low")
				.POST(HttpRequest.BodyPublishers.noBody())
				.build();
		try {
			int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
			if (status == 404 || status == 410) {
				return Result.GONE;
			}
			if (status >= 200 && status < 300) {
				return Result.SENT;
			}
			log.warn("Push service answered {}", status);
			return Result.FAILED;
		} catch (java.io.IOException e) {
			log.warn("Push failed: {}", e.getClass().getSimpleName());
			return Result.FAILED;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Result.FAILED;
		}
	}
}
