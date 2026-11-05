package org.jansathi.outcome;

import java.util.List;
import org.jansathi.content.BundleRepository;
import org.jansathi.web.BadRequestException;
import org.jansathi.web.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Anonymous outcome insights (brief §6.7). A person who opts in on their device adds one to a
 * counter. There is no row per report, so nothing ties a report to a person, a time or an address;
 * the only thing kept is how many. Reading shows rejections only, and only groups of at least
 * {@link #K}: a count smaller than that is never shown, not even inside a larger total. Counts are
 * experience people reported, not scheme rules: they never reach the rules engine.
 */
@RestController
@RequestMapping("/api/v1")
public class OutcomeController {

	static final int K = 10;
	static final List<String> REASON_CODES = List.of("documents_missing", "income_limit", "age_limit", "not_resident", "duplicate", "details_mismatch", "other");

	private final JdbcClient jdbc;
	private final BundleRepository bundles;

	public OutcomeController(JdbcClient jdbc, BundleRepository bundles) {
		this.jdbc = jdbc;
		this.bundles = bundles;
	}

	public record OutcomeRequest(String scheme, String status, String reasonCode) {}

	public record ReasonCodes(List<String> reasonCodes) {}

	public record Reason(String code, long count) {}

	/** @param total people who reported a rejection; null below {@link #K} */
	public record Insights(Long total, List<Reason> reasons) {}

	@GetMapping("/outcomes/reason-codes")
	public ReasonCodes reasonCodes() {
		return new ReasonCodes(REASON_CODES);
	}

	@PostMapping("/outcomes")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void record(@RequestBody OutcomeRequest request) {
		if (request.scheme() == null || bundles.get().scheme(request.scheme()).isEmpty()) {
			throw new BadRequestException("Unknown scheme");
		}
		String reason;
		switch (request.status() == null ? "" : request.status()) {
			case "approved" -> reason = "";
			case "rejected" -> {
				if (request.reasonCode() == null || !REASON_CODES.contains(request.reasonCode())) {
					throw new BadRequestException("reason_code must be one of " + REASON_CODES);
				}
				reason = request.reasonCode();
			}
			default -> throw new BadRequestException("status must be approved or rejected");
		}
		add(request.scheme(), request.status(), reason);
	}

	/** Increment, or create the counter; the second update covers two requests creating it at once. */
	private void add(String scheme, String status, String reason) {
		if (increment(scheme, status, reason) == 0) {
			int created = jdbc.sql("""
					INSERT INTO outcome_count (scheme, status, reason_code, hits) VALUES (:s, :st, :r, 1)
					ON CONFLICT DO NOTHING
					""").param("s", scheme).param("st", status).param("r", reason).update();
			if (created == 0) {
				increment(scheme, status, reason);
			}
		}
	}

	private int increment(String scheme, String status, String reason) {
		return jdbc.sql("UPDATE outcome_count SET hits = hits + 1 WHERE scheme = :s AND status = :st AND reason_code = :r")
				.param("s", scheme).param("st", status).param("r", reason).update();
	}

	@GetMapping("/schemes/{slug}/outcomes")
	public Insights insights(@PathVariable String slug) {
		if (bundles.get().scheme(slug).isEmpty()) {
			throw new NotFoundException("Unknown scheme");
		}
		List<Reason> all = jdbc.sql("SELECT reason_code, hits FROM outcome_count WHERE scheme = :s AND status = 'rejected' ORDER BY hits DESC, reason_code")
				.param("s", slug).query((rs, i) -> new Reason(rs.getString("reason_code"), rs.getLong("hits"))).list();
		long total = all.stream().mapToLong(Reason::count).sum();
		if (total < K) {
			return new Insights(null, List.of());
		}
		return new Insights(total, all.stream().filter(r -> r.count() >= K).toList());
	}
}
