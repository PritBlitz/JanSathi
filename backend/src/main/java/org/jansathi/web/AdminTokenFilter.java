package org.jansathi.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.jansathi.JansathiProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guards {@code /api/v1/admin/**} with a shared bearer token ({@code JANSATHI_ADMIN_TOKEN}).
 * Without a configured token the admin API is switched off. An interim measure for a small team
 * of reviewers; per-person sign-in (OIDC or passkeys, docs/ARCHITECTURE.md §7) replaces it.
 */
@Component
public class AdminTokenFilter extends OncePerRequestFilter {

	private final byte[] token;

	public AdminTokenFilter(JansathiProperties properties) {
		String t = properties.adminToken();
		this.token = t == null || t.isBlank() ? null : t.getBytes(StandardCharsets.UTF_8);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith(request.getContextPath() + "/api/v1/admin/");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (token == null) {
			deny(response, HttpServletResponse.SC_NOT_FOUND, "The admin API is not enabled");
			return;
		}
		String header = request.getHeader("Authorization");
		byte[] given = header != null && header.startsWith("Bearer ")
				? header.substring(7).getBytes(StandardCharsets.UTF_8)
				: new byte[0];
		// Constant-time comparison, so response timing does not reveal the token.
		if (!MessageDigest.isEqual(token, given)) {
			response.setHeader("WWW-Authenticate", "Bearer");
			deny(response, HttpServletResponse.SC_UNAUTHORIZED, "A valid admin token is required");
			return;
		}
		chain.doFilter(request, response);
	}

	private static void deny(HttpServletResponse response, int status, String detail) throws IOException {
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.getWriter().write("{\"status\":" + status + ",\"detail\":\"" + detail + "\"}");
	}
}
