package org.jansathi.reminder;

import java.util.List;
import org.jansathi.account.Accounts.User;
import org.jansathi.JansathiProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Choosing reminder channels. People manage only their own. */
@RestController
@RequestMapping("/api/v1")
public class ReminderController {

	private final Reminders reminders;
	private final PushSender sender;

	public ReminderController(Reminders reminders, PushSender sender) {
		this.reminders = reminders;
		this.sender = sender;
	}

	public record PushKey(String publicKey) {}

	/** Null when reminders are switched off on this server, so the app can hide the option. */
	@GetMapping("/reminders/push-key")
	public PushKey pushKey() {
		return new PushKey(sender.publicKey());
	}

	public record Channels(List<String> channels) {}

	/** Only what this server has configured, so the app offers nothing that can't work. */
	@GetMapping("/reminders/channels")
	public Channels channels() {
		return new Channels(reminders.configuredChannels());
	}

	public record Mine(List<Reminders.ChannelState> channels) {}

	@GetMapping("/me/reminders")
	public Mine mine(User user) {
		return new Mine(reminders.enabled(user.id()));
	}

	public record TelegramLink(String link) {}

	/** Opening the link starts the bot, which ties the chat to this account. */
	@PostMapping("/me/reminders/telegram")
	public TelegramLink telegram(User user) {
		return new TelegramLink(reminders.telegramLink(user.id()));
	}

	@PutMapping("/me/reminders/{channel}")
	public void enable(User user, @PathVariable String channel, @RequestBody(required = false) JsonNode body) {
		reminders.enable(user.id(), channel, body);
	}

	@DeleteMapping("/me/reminders/{channel}")
	public void disable(User user, @PathVariable String channel) {
		reminders.disable(user.id(), channel);
	}

	/** The browser's subscription. p256dh and auth are accepted but not kept: pushes are empty. */
	public record PushSubscription(String endpoint, String p256dh, String auth) {}

	@PutMapping("/me/reminders/push")
	public void subscribe(User user, @RequestBody PushSubscription subscription) {
		reminders.subscribe(user.id(), subscription.endpoint());
	}

	@DeleteMapping("/me/reminders/push")
	public void unsubscribe(User user) {
		reminders.unsubscribe(user.id());
	}

	/** Push is off unless both VAPID keys are set. */
	@Configuration
	@EnableConfigurationProperties(ReminderProperties.class)
	static class Config {

		@Bean
		PushSender pushSender(JansathiProperties properties) {
			JansathiProperties.Push p = properties.push();
			if (p.publicKey() == null || p.privateKey() == null) {
				return new PushSender() {
					@Override
					public String publicKey() {
						return null;
					}

					@Override
					public Result send(String endpoint) {
						return Result.FAILED;
					}
				};
			}
			return new VapidPushSender(p.publicKey(), p.privateKey(), p.subject());
		}
	}
}
