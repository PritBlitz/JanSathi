package org.jansathi.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** {@code /actuator/prometheus} needs {@code JANSATHI_METRICS_TOKEN}; without one it does not exist. */
@Component
public class MetricsTokenFilter extends OncePerRequestFilter {

	private final byte[] token;

	public MetricsTokenFilter(WebProperties properties) {
		this.token = properties.metricsToken() == null ? null : ("Bearer " + properties.metricsToken()).getBytes(StandardCharsets.UTF_8);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith(request.getContextPath() + "/actuator/prometheus");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String header = request.getHeader("Authorization");
		if (token == null || header == null || !MessageDigest.isEqual(token, header.getBytes(StandardCharsets.UTF_8))) {
			response.setStatus(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		chain.doFilter(request, response);
	}
}
