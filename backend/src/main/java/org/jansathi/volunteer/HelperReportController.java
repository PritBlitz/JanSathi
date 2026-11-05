package org.jansathi.volunteer;

import java.util.List;
import java.util.Set;
import org.jansathi.account.Accounts.User;
import org.jansathi.admin.AdminPrincipal;
import org.jansathi.web.BadRequestException;
import org.jansathi.web.NotFoundException;
import org.jansathi.web.Scrub;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Reporting a helper, and the moderators' queue. */
@RestController
@RequestMapping("/api/v1")
public class HelperReportController {

	private static final Set<String> CLOSED = Set.of("resolved", "rejected");

	private final HelperReports reports;
	private final CaseShares shares;

	public HelperReportController(HelperReports reports, CaseShares shares) {
		this.reports = reports;
		this.shares = shares;
	}

	/** Who can be reported: every helper who joined one of this person's cases. */
	@GetMapping("/me/helpers")
	public List<CaseShares.PastHelper> helpers(User user) {
		return shares.helpersOf(user);
	}

	public record ReportRequest(String shareId, String reason, String note) {}

	public record Receipt(String id) {}

	@GetMapping("/helper-reports/reasons")
	public Set<String> reasons() {
		return HelperReports.REASONS;
	}

	@PostMapping("/me/helper-reports")
	@ResponseStatus(HttpStatus.CREATED)
	public Receipt report(User user, @RequestBody ReportRequest request) {
		return new Receipt(reports.file(user, request.shareId(), request.reason(), request.note()));
	}

	@GetMapping("/admin/helper-reports")
	public List<HelperReports.Report> list(@RequestParam(required = false) String status, @RequestParam(defaultValue = "100") int limit) {
		if (status != null && !status.equals("open") && !CLOSED.contains(status)) {
			throw new BadRequestException("status must be open, resolved or rejected");
		}
		return reports.list(status, Math.clamp(limit, 1, 500));
	}

	/** @param deactivateHelper also end the helper's role; only an admin may ask for this */
	public record ResolveRequest(String status, String resolution, Boolean deactivateHelper) {}

	@PostMapping("/admin/helper-reports/{id}/resolve")
	public void resolve(AdminPrincipal actor, @PathVariable String id, @RequestBody ResolveRequest request) {
		if (request.status() == null || !CLOSED.contains(request.status())) {
			throw new BadRequestException("status must be resolved or rejected");
		}
		boolean deactivate = Boolean.TRUE.equals(request.deactivateHelper());
		if (deactivate && !actor.isAdmin()) {
			throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN, "Only an admin can deactivate a helper");
		}
		String resolution = request.resolution() == null ? null : Scrub.numbers(request.resolution().strip());
		if (resolution != null && resolution.length() > 1000) {
			throw new BadRequestException("resolution must be at most 1000 characters");
		}
		if (!reports.resolve(id, request.status(), resolution, deactivate, actor.label())) {
			throw new NotFoundException("No open report with id " + id);
		}
	}
}
