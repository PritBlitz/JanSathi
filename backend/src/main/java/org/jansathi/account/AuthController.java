package org.jansathi.account;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jansathi.content.Languages;
import org.jansathi.web.BadRequestException;
import org.jansathi.web.ClientAddress;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sign in with a mobile number and a one-time code. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

	/** Codes per client address per hour, held in memory only (addresses are never stored). */
	private static final int CODES_PER_ADDRESS_PER_HOUR = 10;

	private final Accounts accounts;
	private final Map<String, Deque<Instant>> recentByAddress = new ConcurrentHashMap<>();

	private final ClientAddress addresses;

	public AuthController(Accounts accounts, ClientAddress addresses) {
		this.accounts = accounts;
		this.addresses = addresses;
	}

	public record CodeRequest(String phone, String lang) {}

	/** @param devCode only with the development SMS stand-in: the code to show on screen */
	public record CodeResponse(boolean sent, String devCode) {}

	public record VerifyRequest(String phone, String code, String lang) {}

	@PostMapping("/code")
	public CodeResponse code(@RequestBody CodeRequest request, HttpServletRequest http) {
		limitByAddress(addresses.of(http));
		var sent = accounts.sendCode(request.phone(), Languages.orEnglish(request.lang()));
		return new CodeResponse(true, sent.devCode());
	}

	@PostMapping("/verify")
	public Accounts.SignedIn verify(@RequestBody VerifyRequest request) {
		return accounts.verify(request.phone(), request.code(), Languages.orEnglish(request.lang()));
	}

	@PostMapping("/sign-out")
	public void signOut(HttpServletRequest http) {
		String token = CurrentUserResolver.bearer(http);
		if (token != null) {
			accounts.signOut(token);
		}
	}

	private void limitByAddress(String address) {
		Deque<Instant> times = recentByAddress.computeIfAbsent(address, a -> new ArrayDeque<>());
		synchronized (times) {
			Instant hourAgo = Instant.now().minusSeconds(3600);
			while (!times.isEmpty() && times.peekFirst().isBefore(hourAgo)) {
				times.pollFirst();
			}
			if (times.size() >= CODES_PER_ADDRESS_PER_HOUR) {
				throw new BadRequestException("Too many codes from this device. Try again later.");
			}
			times.addLast(Instant.now());
		}
	}
}
