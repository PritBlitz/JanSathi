package org.jansathi.report;

import java.util.List;
import java.util.Set;
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

/** Filing a grievance and reading its answer; the queue is under {@code /admin}. */
@RestController
@RequestMapping("/api/v1")
public class GrievanceController {

	private static final Set<String> CLOSED = Set.of("resolved", "rejected");

	private final Grievances grievances;

	public GrievanceController(Grievances grievances) {
		this.grievances = grievances;
	}

	public record GrievanceRequest(String category, String message, String contact, String lang) {}

	public record Receipt(String id) {}

	public record ResolveRequest(String status, String response) {}

	@GetMapping("/grievances/categories")
	public Set<String> categories() {
		return Grievances.CATEGORIES;
	}

	@PostMapping("/grievances")
	@ResponseStatus(HttpStatus.CREATED)
	public Receipt file(@RequestBody GrievanceRequest request) {
		return new Receipt(grievances.file(request.category(), request.message(), request.contact(), request.lang()));
	}

	@GetMapping("/grievances/{id}")
	public Grievances.Status status(@PathVariable String id) {
		return grievances.status(id).orElseThrow(() -> new NotFoundException("No grievance with this reference"));
	}

	@GetMapping("/admin/grievances")
	public List<Grievances.Grievance> list(@RequestParam(required = false) String status, @RequestParam(defaultValue = "100") int limit) {
		if (status != null && !status.equals("open") && !CLOSED.contains(status)) {
			throw new BadRequestException("status must be open, resolved or rejected");
		}
		return grievances.list(status, Math.clamp(limit, 1, 500));
	}

	@PostMapping("/admin/grievances/{id}/resolve")
	public void resolve(AdminPrincipal actor, @PathVariable String id, @RequestBody ResolveRequest request) {
		if (request.status() == null || !CLOSED.contains(request.status())) {
			throw new BadRequestException("status must be resolved or rejected");
		}
		String response = request.response() == null ? "" : Scrub.numbers(request.response().strip());
		if (response.isEmpty() || response.length() > Grievances.MAX_RESPONSE) {
			throw new BadRequestException("A response needs 1 to " + Grievances.MAX_RESPONSE + " characters");
		}
		if (!grievances.resolve(id, request.status(), response, actor.label())) {
			throw new NotFoundException("No open grievance with id " + id);
		}
	}
}
