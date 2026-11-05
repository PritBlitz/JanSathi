package org.jansathi.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import org.jansathi.JansathiProperties;
import org.jansathi.admin.AdminPrincipal;
import org.jansathi.admin.StaffAccounts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guards {@code /api/v1/admin/**}. Each person signs in to their own staff account (admin or
 * reviewer). {@code JANSATHI_ADMIN_TOKEN} stays as a bootstrap: its holder acts as an admin, to
 * create the first accounts, and every use is logged and audited. With neither a token nor an active
 * staff account the admin API does not exist (404).
 */
@Component
public class AdminAuthFilter extends OncePerRequestFilter implements Ordered {

	private static final Logger log = LoggerFactory.getLogger(AdminAuthFilter.class);
	private static final String BASE = "/api/v1/admin/";

	private final byte[] token;
	private final StaffAccounts staff;

	public AdminAuthFilter(JansathiProperties properties, StaffAccounts staff) {
		String t = properties.adminToken();
		this.token = t == null || t.isBlank() ? null : t.getBytes(StandardCharsets.UTF_8);
		this.staff = staff;
	}

	@Override
	public int getOrder() {
		return RateLimitFilter.ORDER + 1;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith(request.getContextPath() + BASE);
	}

	/** What only an admin may do: people, helpers and the record of what staff did. */
	static boolean adminOnly(String method, String path) {
		return path.startsWith("staff") || path.startsWith("audit") || path.startsWith("maintenance")
				|| (path.startsWith("volunteers") && !"GET".equals(method));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (token == null && !staff.anyActive()) {
			deny(response, HttpServletResponse.SC_NOT_FOUND, "The admin API is not enabled");
			return;
		}
		String path = request.getRequestURI().substring(request.getContextPath().length() + BASE.length());
		if (path.startsWith("auth/")) {
			chain.doFilter(request, response);
			return;
		}
		String header = request.getHeader("Authorization");
		String bearer = header != null && header.startsWith("Bearer ") ? header.substring(7).strip() : "";
		Optional<AdminPrincipal> principal = resolve(bearer);
		if (principal.isEmpty()) {
			response.setHeader("WWW-Authenticate", "Bearer");
			deny(response, HttpServletResponse.SC_UNAUTHORIZED, "Sign in to the admin screens first");
			return;
		}
		if (adminOnly(request.getMethod(), path) && !principal.get().isAdmin()) {
			deny(response, HttpServletResponse.SC_FORBIDDEN, "Only an admin can do this");
			return;
		}
		request.setAttribute(AdminPrincipal.ATTRIBUTE, principal.get());
		chain.doFilter(request, response);
	}

	private Optional<AdminPrincipal> resolve(String bearer) {
		// Constant-time comparison, so response timing does not reveal the token.
		if (token != null && MessageDigest.isEqual(token, bearer.getBytes(StandardCharsets.UTF_8))) {
			log.warn("Admin API used with the bootstrap token. Create staff accounts and remove JANSATHI_ADMIN_TOKEN.");
			return Optional.of(AdminPrincipal.BOOTSTRAP);
		}
		return staff.bySession(bearer);
	}

	private static void deny(HttpServletResponse response, int status, String detail) throws IOException {
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.getWriter().write("{\"status\":" + status + ",\"detail\":\"" + detail + "\"}");
	}
}
