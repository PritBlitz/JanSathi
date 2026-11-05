package org.jansathi.account;

import java.util.List;
import org.jansathi.web.NotFoundException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registering and deactivating helpers (NGO and CSC staff). Listing is open to reviewers, changes
 * only to admins ({@code AdminAuthFilter}); every call is in the admin audit.
 */
@RestController
@RequestMapping("/api/v1/admin/volunteers")
public class AdminVolunteerController {

	private final Accounts accounts;

	public AdminVolunteerController(Accounts accounts) {
		this.accounts = accounts;
	}

	public record VolunteerRequest(String phone, String name, String organisation) {}

	@GetMapping
	public List<Accounts.Helper> list(@RequestParam(name = "include_deactivated", defaultValue = "false") boolean includeDeactivated) {
		return accounts.volunteers(includeDeactivated);
	}

	@PostMapping
	public Accounts.User add(@RequestBody VolunteerRequest request) {
		return accounts.makeVolunteer(request.phone(), request.name(), request.organisation());
	}

	@DeleteMapping("/{id}")
	public void remove(@PathVariable String id) {
		if (!accounts.deactivateHelper(id, "admin")) {
			throw new NotFoundException("No active helper with id " + id);
		}
	}
}
