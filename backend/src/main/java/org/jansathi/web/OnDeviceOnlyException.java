package org.jansathi.web;

/** Maps to 403 with {@code code: on_device_only}: the app keeps working, on the device only. */
public class OnDeviceOnlyException extends RuntimeException {

	public static final String CODE = "on_device_only";

	public OnDeviceOnlyException() {
		super("Under 18: this stays on your device. Nothing is saved on our server.");
	}
}
