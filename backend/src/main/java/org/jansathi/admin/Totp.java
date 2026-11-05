package org.jansathi.admin;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Authenticator-app codes (RFC 6238: HMAC-SHA1, 30 seconds, 6 digits), what every such app speaks. */
final class Totp {

	private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
	private static final long STEP_SECONDS = 30;

	private Totp() {}

	static long step(long epochSeconds) {
		return epochSeconds / STEP_SECONDS;
	}

	static String code(byte[] secret, long step) {
		try {
			Mac mac = Mac.getInstance("HmacSHA1");
			mac.init(new SecretKeySpec(secret, "HmacSHA1"));
			byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
			int o = h[h.length - 1] & 0xF;
			int n = ((h[o] & 0x7F) << 24) | ((h[o + 1] & 0xFF) << 16) | ((h[o + 2] & 0xFF) << 8) | (h[o + 3] & 0xFF);
			return String.format("%06d", n % 1_000_000);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	/** The step this code belongs to, one step either side for clock drift, or -1. */
	static long matchingStep(byte[] secret, String given, long now) {
		for (long s = now - 1; s <= now + 1; s++) {
			if (code(secret, s).equals(given)) {
				return s;
			}
		}
		return -1;
	}

	static String base32(byte[] bytes) {
		StringBuilder out = new StringBuilder();
		int buffer = 0;
		int bits = 0;
		for (byte b : bytes) {
			buffer = (buffer << 8) | (b & 0xFF);
			bits += 8;
			while (bits >= 5) {
				out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
				bits -= 5;
			}
		}
		if (bits > 0) {
			out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
		}
		return out.toString();
	}

	static byte[] fromBase32(String text) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int buffer = 0;
		int bits = 0;
		for (char c : text.toCharArray()) {
			buffer = (buffer << 5) | BASE32.indexOf(c);
			bits += 5;
			if (bits >= 8) {
				out.write((buffer >> (bits - 8)) & 0xFF);
				bits -= 8;
			}
		}
		return out.toByteArray();
	}

	static String uri(String username, String base32Secret) {
		String label = URLEncoder.encode("JanSathi:" + username, StandardCharsets.UTF_8).replace("+", "%20");
		return "otpauth://totp/" + label + "?secret=" + base32Secret + "&issuer=JanSathi&algorithm=SHA1&digits=6&period=30";
	}
}
