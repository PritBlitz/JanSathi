package org.jansathi.account;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.jansathi.JansathiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Keys derived from one server secret ({@code JANSATHI_SECRET}): an HMAC key for phone hashes and
 * OTP codes, and an AES-256-GCM key for personal data at rest. The database holds only hashes and
 * ciphertext. Without a configured secret (local development) a random one is used and data does
 * not survive a restart.
 */
@Component
public class Crypto {

	private static final Logger log = LoggerFactory.getLogger(Crypto.class);
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final int IV_BYTES = 12;

	private final byte[] hmacKey;
	private final SecretKeySpec aesKey;

	public Crypto(JansathiProperties properties) {
		byte[] secret;
		if (properties.secret() == null || properties.secret().isBlank()) {
			secret = randomBytes(32);
			log.warn("JANSATHI_SECRET is not set: using a random key, so accounts and saved data are lost on restart");
		} else {
			secret = properties.secret().getBytes(StandardCharsets.UTF_8);
		}
		this.hmacKey = hmac(secret, "jansathi:hmac".getBytes(StandardCharsets.UTF_8));
		this.aesKey = new SecretKeySpec(hmac(secret, "jansathi:aes".getBytes(StandardCharsets.UTF_8)), "AES");
	}

	public static byte[] randomBytes(int n) {
		byte[] b = new byte[n];
		RANDOM.nextBytes(b);
		return b;
	}

	public static String randomToken() {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
	}

	/** A random code of {@code length} digits. */
	public static String randomDigits(int length) {
		StringBuilder s = new StringBuilder();
		for (int i = 0; i < length; i++) {
			s.append(RANDOM.nextInt(10));
		}
		return s.toString();
	}

	private static byte[] hmac(byte[] key, byte[] data) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			return mac.doFinal(data);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Keyed hash (hex): the same input always gives the same value, but it can't be reversed. */
	public String keyedHash(String value) {
		return HexFormat.of().formatHex(hmac(hmacKey, value.getBytes(StandardCharsets.UTF_8)));
	}

	/** Unkeyed SHA-256 (hex), for random tokens that are already unguessable. */
	public static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	public static boolean sameHash(String a, String b) {
		return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
	}

	/** AES-256-GCM; output is base64(iv ‖ ciphertext ‖ tag). */
	public String encrypt(String plain) {
		try {
			byte[] iv = randomBytes(IV_BYTES);
			Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
			c.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(128, iv));
			byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
			byte[] out = new byte[iv.length + ct.length];
			System.arraycopy(iv, 0, out, 0, iv.length);
			System.arraycopy(ct, 0, out, iv.length, ct.length);
			return Base64.getEncoder().encodeToString(out);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	public String decrypt(String encoded) {
		try {
			byte[] in = Base64.getDecoder().decode(encoded);
			Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
			c.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(128, in, 0, IV_BYTES));
			return new String(c.doFinal(in, IV_BYTES, in.length - IV_BYTES), StandardCharsets.UTF_8);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("Stored data cannot be decrypted (was JANSATHI_SECRET changed?)", e);
		}
	}
}
