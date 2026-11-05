package org.jansathi.admin;

import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.List;
import org.jansathi.account.CurrentUserResolver;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Signing in to the admin screens, managing staff accounts and reading the admin audit. */
@RestController
@RequestMapping("/api/v1/admin")
public class StaffController {

	private final StaffAccounts staff;
	private final JdbcClient jdbc;

	public StaffController(StaffAccounts staff, JdbcClient jdbc) {
		this.staff = staff;
		this.jdbc = jdbc;
	}

	public record SetupRequest(String username, String setupCode, String password) {}

	public record ConfirmRequest(String username, String setupCode, String code) {}

	public record LoginRequest(String username, String password, String code) {}

	@PostMapping("/auth/setup")
	public StaffAccounts.Setup setup(@RequestBody SetupRequest r) {
		return staff.choosePassword(r.username(), r.setupCode(), r.password());
	}

	@PostMapping("/auth/confirm")
	public void confirm(@RequestBody ConfirmRequest r) {
		staff.confirm(r.username(), r.setupCode(), r.code());
	}

	@PostMapping("/auth/login")
	public StaffAccounts.SignedIn login(@RequestBody LoginRequest r) {
		return staff.login(r.username(), r.password(), r.code());
	}

	@PostMapping("/auth/logout")
	public void logout(HttpServletRequest http) {
		String token = CurrentUserResolver.bearer(http);
		if (token != null) {
			staff.logout(token);
		}
	}

	@GetMapping("/me")
	public AdminPrincipal me(AdminPrincipal actor) {
		return actor;
	}

	@GetMapping("/staff")
	public List<StaffAccounts.Staff> list() {
		return staff.list();
	}

	public record StaffRequest(String username, String displayName, String role) {}

	@PostMapping("/staff")
	@ResponseStatus(HttpStatus.CREATED)
	public StaffAccounts.Created create(AdminPrincipal actor, @RequestBody StaffRequest r) {
		return staff.create(r.username(), r.displayName(), r.role(), actor.label());
	}

	@PostMapping("/staff/{id}/reset")
	public StaffAccounts.Created reset(@PathVariable String id) {
		return staff.reset(id);
	}

	@DeleteMapping("/staff/{id}")
	public void deactivate(@PathVariable String id) {
		staff.deactivate(id);
	}

	public record AuditLine(Instant at, String actor, String action, String target, int status) {}

	@GetMapping("/audit")
	public List<AuditLine> audit(@RequestParam(defaultValue = "200") int limit) {
		return jdbc.sql("SELECT * FROM admin_audit ORDER BY created_at DESC LIMIT :n").param("n", Math.clamp(limit, 1, 1000))
				.query((rs, i) -> new AuditLine(rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getString("actor_label"),
						rs.getString("action"), rs.getString("target"), rs.getInt("status")))
				.list();
	}
}
