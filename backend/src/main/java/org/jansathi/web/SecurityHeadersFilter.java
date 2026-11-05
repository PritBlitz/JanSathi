package org.jansathi.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Baseline headers on every response, the rate-limit answer included. The policy names exactly what
 * the PWA uses: its own files, Tesseract's WebAssembly and worker (document check), OpenStreetMap
 * tiles, and the three public lookups behind "nearby help" (frontend/src/lib/places.js).
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter implements Ordered {

	static final String CSP = String.join("; ",
			"default-src 'self'",
			"script-src 'self' 'wasm-unsafe-eval'",
			"style-src 'self' 'unsafe-inline'",
			"img-src 'self' data: blob: https://tile.openstreetmap.org",
			"font-src 'self'",
			"connect-src 'self' https://overpass-api.de https://nominatim.openstreetmap.org https://api.postalpincode.in",
			"media-src 'self' blob:",
			"worker-src 'self' blob:",
			"manifest-src 'self'",
			"object-src 'none'",
			"base-uri 'self'",
			"form-action 'self'",
			"frame-ancestors 'none'");

	@Override
	public int getOrder() {
		return RateLimitFilter.ORDER - 1;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		response.setHeader("X-Content-Type-Options", "nosniff");
		// OpenStreetMap's tile policy needs a Referer. This sends only our origin, never a path.
		response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
		response.setHeader("X-Frame-Options", "DENY");
		// Voice input and "nearby help" need these on our own pages; nothing embedded may use them.
		response.setHeader("Permissions-Policy", "camera=(), geolocation=(self), microphone=(self)");
		response.setHeader("Content-Security-Policy", CSP);
		// Browsers ignore this over plain http, so local development is not affected.
		response.setHeader("Strict-Transport-Security", "max-age=31536000");
		chain.doFilter(request, response);
	}
}
