package org.jansathi.admin;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.jansathi.account.Crypto;

/** PBKDF2-HMAC-SHA256 from the JDK, at OWASP's iteration count. Stored as {@code pbkdf2$iterations$salt$hash}. */
final class Passwords {

	static final int ITERATIONS = 600_000;
	static final int MIN_LENGTH = 12;
	private static final Base64.Encoder B64 = Base64.getEncoder().withoutPadding();

	private Passwords() {}

	private static byte[] derive(String password, byte[] salt, int iterations) {
		try {
			return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
					.generateSecret(new PBEKeySpec(password.toCharArray(), salt, iterations, 256)).getEncoded();
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	static String hash(String password) {
		byte[] salt = Crypto.randomBytes(16);
		return "pbkdf2$" + ITERATIONS + "$" + B64.encodeToString(salt) + "$" + B64.encodeToString(derive(password, salt, ITERATIONS));
	}

	static boolean matches(String password, String stored) {
		String[] p = stored.split("\\$");
		byte[] expected = Base64.getDecoder().decode(p[3]);
		return MessageDigest.isEqual(expected, derive(password, Base64.getDecoder().decode(p[2]), Integer.parseInt(p[1])));
	}
}
