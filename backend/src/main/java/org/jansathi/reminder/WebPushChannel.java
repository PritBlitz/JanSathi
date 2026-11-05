package org.jansathi.reminder;

import org.springframework.stereotype.Component;

/** Web Push through {@link PushSender}. Pushes are empty, so the app's service worker does the wording. */
@Component
public class WebPushChannel implements ReminderChannel {

	private final PushSender sender;

	public WebPushChannel(PushSender sender) {
		this.sender = sender;
	}

	@Override
	public String id() {
		return "web_push";
	}

	@Override
	public boolean configured() {
		return sender.enabled();
	}

	@Override
	public Result send(String address, Message message) {
		return switch (sender.send(address)) {
			case SENT -> Result.SENT;
			case GONE -> Result.GONE;
			case FAILED -> Result.FAILED;
		};
	}
}
