package org.jansathi.reminder;

/**
 * Sends an empty push to one browser. Empty on purpose: the push service (Google, Mozilla, Apple,
 * Microsoft) learns only that a reminder was sent, and the app's service worker words it.
 */
public interface PushSender {

	enum Result {
		SENT,
		/** The browser unsubscribed or the subscription expired: forget it. */
		GONE,
		/** A passing failure: keep the subscription and try next time. */
		FAILED
	}

	/** The VAPID public key for the browser to subscribe with; null when push is switched off. */
	String publicKey();

	Result send(String endpoint);

	default boolean enabled() {
		return publicKey() != null;
	}
}
