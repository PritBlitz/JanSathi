package org.jansathi.account;

import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Registering helpers (NGO and CSC staff). Behind the admin token ({@code AdminTokenFilter}). */
@RestController
@RequestMapping("/api/v1/admin/volunteers")
public class AdminVolunteerController {

	private final Accounts accounts;

	public AdminVolunteerController(Accounts accounts) {
		this.accounts = accounts;
	}

	public record VolunteerRequest(String phone, String name, String organisation) {}

	@GetMapping
	public List<Accounts.User> list() {
		return accounts.volunteers();
	}

	@PostMapping
	public Accounts.User add(@RequestBody VolunteerRequest request) {
		return accounts.makeVolunteer(request.phone(), request.name(), request.organisation());
	}

	@DeleteMapping("/{id}")
	public void remove(@PathVariable String id) {
		accounts.removeVolunteer(id);
	}
}
