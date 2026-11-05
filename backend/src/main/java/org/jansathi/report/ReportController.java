package org.jansathi.report;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jansathi.content.BundleRepository;
import org.jansathi.content.Languages;
import org.jansathi.web.BadRequestException;
import org.jansathi.web.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Report outdated info" (brief §6.3): the public endpoint files a report; the admin endpoints
 * (behind {@link org.jansathi.web.AdminAuthFilter}) list and close them. A report holds no
 * personal data: no IP address, no profile, and long digit runs are removed from the note.
 */
@RestController
@RequestMapping("/api/v1")
public class ReportController {

	private static final Set<String> REASONS = Set.of("outdated", "wrong", "broken_link", "other");
	private static final Set<String> CLOSED = Set.of("resolved", "rejected");
	/** Runs of 9 or more digits (allowing spaces and hyphens): Aadhaar, bank account, phone numbers. */
	private static final Pattern PERSONAL_NUMBER = Pattern.compile("\\d(?:[\\s-]?\\d){8,}");
	private static final int MAX_NOTE = 1000;

	private final BundleRepository bundles;
	private final ReportRepository reports;

	public ReportController(BundleRepository bundles, ReportRepository reports) {
		this.bundles = bundles;
		this.reports = reports;
	}

	public record ReportRequest(String scheme, String reason, String note, String lang) {}

	public record ReportReceipt(String id) {}

	public record ResolveRequest(String status, String resolution) {}

	static String redact(String text) {
		return PERSONAL_NUMBER.matcher(text).replaceAll("[number removed]");
	}

	@PostMapping("/reports")
	@ResponseStatus(HttpStatus.CREATED)
	public ReportReceipt report(@RequestBody ReportRequest request) {
		if (request.scheme() == null || bundles.get().scheme(request.scheme()).isEmpty()) {
			throw new BadRequestException("Unknown scheme");
		}
		if (request.reason() == null || !REASONS.contains(request.reason())) {
			throw new BadRequestException("reason must be one of " + REASONS);
		}
		String note = request.note() == null ? "" : request.note().strip();
		if (note.length() > MAX_NOTE) {
			throw new BadRequestException("note must be at most " + MAX_NOTE + " characters");
		}
		String id = UUID.randomUUID().toString();
		reports.insert(new ReportRepository.Report(
				id, Instant.now(), request.scheme(), request.reason(), redact(note),
				Languages.orEnglish(request.lang()), bundles.get().version(), "open", null, null));
		return new ReportReceipt(id);
	}

	@GetMapping("/admin/reports")
	public List<ReportRepository.Report> list(
			@RequestParam(required = false) String status, @RequestParam(defaultValue = "100") int limit) {
		if (status != null && !status.equals("open") && !CLOSED.contains(status)) {
			throw new BadRequestException("status must be open, resolved or rejected");
		}
		return reports.list(status, Math.clamp(limit, 1, 500));
	}

	@PostMapping("/admin/reports/{id}/resolve")
	public void resolve(@PathVariable String id, @RequestBody ResolveRequest request) {
		if (request.status() == null || !CLOSED.contains(request.status())) {
			throw new BadRequestException("status must be resolved or rejected");
		}
		String resolution = request.resolution() == null ? null : redact(request.resolution().strip());
		if (resolution != null && resolution.length() > MAX_NOTE) {
			throw new BadRequestException("resolution must be at most " + MAX_NOTE + " characters");
		}
		if (!reports.resolve(id, request.status(), resolution)) {
			throw new NotFoundException("No open report with id " + id);
		}
	}
}
