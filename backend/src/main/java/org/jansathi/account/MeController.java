package org.jansathi.account;

import java.time.Duration;
import java.util.List;
import org.jansathi.account.Accounts.User;
import org.jansathi.guide.GuideService;
import org.jansathi.volunteer.CaseShares;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * A signed-in person's own data: the household the guide remembers, who they shared their case
 * with, what helpers did (audit log and notes), and deleting everything.
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

	private final Accounts accounts;
	private final Households households;
	private final CaseShares shares;
	private final GuideService guide;

	public MeController(Accounts accounts, Households households, CaseShares shares, GuideService guide) {
		this.accounts = accounts;
		this.households = households;
		this.shares = shares;
		this.guide = guide;
	}

	@GetMapping
	public User me(User user) {
		return user;
	}

	/** Deletes the account and everything saved with it: answers, conversation, shares, notes, audit log. */
	@DeleteMapping
	public void delete(User user) {
		accounts.delete(user.id());
	}

	/** @param onDeviceOnly true while the person is under 18 by their own answer (PRIVACY §6) */
	public record Restrictions(boolean onDeviceOnly) {}

	@GetMapping("/restrictions")
	public Restrictions restrictions(User user) {
		return new Restrictions(accounts.onDeviceOnly(user.id()));
	}

	@GetMapping("/household")
	public JsonNode household(User user) {
		return households.get(user.id());
	}

	@PutMapping("/household")
	public JsonNode saveHousehold(User user, @RequestBody JsonNode household) {
		return households.put(user.id(), household);
	}

	@GetMapping("/audit")
	public List<Accounts.AuditEntry> audit(User user) {
		return accounts.auditFor(user.id());
	}

	public record ShareRequest(List<String> scopes, Integer hours, Boolean helpRequest, String lang) {}

	@GetMapping("/shares")
	public List<CaseShares.Share> shares(User user) {
		return shares.activeFor(user);
	}

	@PostMapping("/shares")
	public CaseShares.Share share(User user, @RequestBody ShareRequest request) {
		Duration d = Duration.ofHours(request.hours() == null ? 24 : request.hours());
		return shares.create(user, request.scopes(), d, Boolean.TRUE.equals(request.helpRequest()), request.lang());
	}

	@DeleteMapping("/shares/{id}")
	public void stopSharing(User user, @PathVariable String id) {
		shares.revoke(user, id);
	}

	@GetMapping("/notes")
	public List<CaseShares.Note> notes(User user) {
		return shares.notes(null, user.id());
	}

	@GetMapping("/conversation")
	public List<GuideService.Line> conversation(User user) {
		return guide.history(user.id(), 100);
	}

	@DeleteMapping("/conversation")
	public void forgetConversation(User user) {
		guide.forget(user.id());
		accounts.audit(user.id(), user.id(), "You", "deleted_conversation", null);
	}
}
