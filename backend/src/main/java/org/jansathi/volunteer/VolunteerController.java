package org.jansathi.volunteer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jansathi.account.Accounts;
import org.jansathi.account.Accounts.User;
import org.jansathi.account.Households;
import org.jansathi.content.BundleRepository;
import org.jansathi.content.ContentBundle;
import org.jansathi.guide.GuideService;
import org.jansathi.provider.llm.LlmProvider.Message;
import org.jansathi.rules.MatchResponse;
import org.jansathi.rules.RulesEngine;
import org.jansathi.web.BadRequestException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The helper portal (brief §6.6). Registered helpers see only cases shared with them, only the
 * parts the person allowed, and only while the share lasts. Every look is in the person's audit
 * log. The phone number is never shown.
 */
@RestController
@RequestMapping("/api/v1/volunteer")
public class VolunteerController {

	private final CaseShares shares;
	private final Households households;
	private final Accounts accounts;
	private final BundleRepository bundles;
	private final RulesEngine engine;
	private final GuideService guide;

	public VolunteerController(CaseShares shares, Households households, Accounts accounts, BundleRepository bundles, RulesEngine engine, GuideService guide) {
		this.shares = shares;
		this.households = households;
		this.accounts = accounts;
		this.bundles = bundles;
		this.engine = engine;
		this.guide = guide;
	}

	public record Dashboard(User helper, List<CaseShares.CaseSummary> openRequests, List<CaseShares.CaseSummary> myCases) {}

	@GetMapping
	public Dashboard dashboard(User helper) {
		return new Dashboard(helper, shares.openRequests(helper), shares.casesOf(helper));
	}

	public record JoinRequest(String code) {}

	@PostMapping("/join")
	public CaseShares.CaseSummary join(User helper, @RequestBody JoinRequest request) {
		return shares.joinByCode(helper, request.code());
	}

	@PostMapping("/requests/{id}/pick-up")
	public CaseShares.CaseSummary pickUp(User helper, @PathVariable String id) {
		return shares.pickUp(helper, id);
	}

	@DeleteMapping("/cases/{id}")
	public void leave(User helper, @PathVariable String id) {
		shares.leave(helper, id);
	}

	/**
	 * What the helper may see of one case.
	 *
	 * @param members each member's name, answers, rules engine results and which fields are masked
	 *     (with the profile scope). Results are worked out on the masked answers, so a rule trace
	 *     cannot give away a hidden answer: those rules show as "need more information".
	 * @param checklist only with the checklist scope
	 * @param applications the person's own application tracking; only with the checklist scope
	 * @param conversation only with both the conversation and sensitive scopes: free text can hold
	 *     anything the person told Saathi, including the answers masked here
	 */
	public record CaseView(
			String id, Set<String> scopes, List<Map<String, Object>> members, JsonNode checklist, JsonNode applications,
			List<GuideService.Line> conversation, List<CaseShares.Note> notes) {}

	@GetMapping("/cases/{id}")
	public CaseView view(User helper, @PathVariable String id) {
		CaseShares.Access a = shares.access(helper, id);
		ObjectNode h = CaseMasking.visible(households.get(a.ownerId()), a.scopes());
		ContentBundle b = bundles.get();
		List<Map<String, Object>> members = new ArrayList<>();
		if (a.scopes().contains("profile")) {
			for (JsonNode m : h.path("members")) {
				Set<String> skipped = new HashSet<>();
				m.path("skipped").forEach(x -> skipped.add(x.asString()));
				JsonNode profile = m.path("profile");
				String state = profile.path("state").isString() ? profile.get("state").asString() : null;
				MatchResponse match = engine.match(b.version(), b.schemesFor(state), profile, b.fields(), skipped, java.time.LocalDate.now());
				Map<String, Object> mm = new LinkedHashMap<>();
				mm.put("name", m.path("name").isString() ? m.get("name").asString() : null);
				mm.put("profile", profile);
				mm.put("skipped", skipped);
				mm.put("masked", CaseMasking.masked(a.scopes()));
				mm.put("results", match.results());
				members.add(mm);
			}
		}
		boolean checklist = a.scopes().contains("checklist");
		boolean conversation = a.scopes().contains("conversation") && a.scopes().contains("sensitive");
		accounts.audit(a.ownerId(), helper.id(), helper.label(), "helper_viewed", seen(a.scopes(), h));
		return new CaseView(id, a.scopes(), members,
				checklist ? h.path("checklist") : null,
				checklist && h.has("applications") ? h.get("applications") : null,
				conversation ? guide.history(a.ownerId(), 50) : List.of(),
				shares.notes(id, null));
	}

	/** The audit line: which scopes were used and, by name only, which sensitive answers were shown. */
	private static String seen(Set<String> scopes, ObjectNode visible) {
		List<String> sensitive = scopes.contains("sensitive") && scopes.contains("profile") ? CaseMasking.answered(visible) : List.of();
		return "Saw: " + String.join(", ", scopes) + (sensitive.isEmpty() ? "" : " (sensitive answers: " + String.join(", ", sensitive) + ")");
	}

	public record NoteRequest(String text) {}

	@PostMapping("/cases/{id}/notes")
	public CaseShares.Note note(User helper, @PathVariable String id, @RequestBody NoteRequest request) {
		return shares.addNote(helper, id, request.text());
	}

	public record AssistLine(String role, String text) {}

	public record AssistRequest(List<AssistLine> messages, String lang) {}

	/** Saathi helps the helper with this case: it can look, never change. Nothing is saved. */
	@PostMapping("/cases/{id}/assist")
	public GuideService.Reply assist(User helper, @PathVariable String id, @RequestBody AssistRequest request) {
		CaseShares.Access a = shares.access(helper, id);
		if (request.messages() == null || request.messages().isEmpty() || request.messages().size() > 30) {
			throw new BadRequestException("Send 1 to 30 messages");
		}
		List<Message> conversation = request.messages().stream()
				.map(m -> "user".equals(m.role()) ? Message.user(m.text()) : Message.assistant(m.text()))
				.toList();
		// The guide works on the same masked copy the helper sees, never the full household.
		ObjectNode h = CaseMasking.visible(households.get(a.ownerId()), a.scopes());
		accounts.audit(a.ownerId(), helper.id(), helper.label(), "helper_asked_ai", null);
		return guide.assistHelper(helper, h, conversation, request.lang());
	}
}
