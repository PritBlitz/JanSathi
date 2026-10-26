package org.jansathi.account;

/** Sends a one-time code to a phone. A provider behind an interface (docs/ARCHITECTURE.md §6). */
public interface SmsSender {

	/**
	 * @return the code itself when this sender is the development stand-in (shown on screen), or
	 *     null when a real SMS was sent
	 */
	String sendCode(String phone, String code, String lang);
}
