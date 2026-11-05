package org.jansathi.web;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Requests per client address per minute. Held in this process: one instance needs no Redis, and two
 * instances would each allow the limit (docs/OPERATIONS.md). Addresses are never stored or logged.
 * The first matching class applies; a CSC or a shared mobile network puts many people behind one
 * address, so the limits stop floods, not people.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter implements Ordered {

	private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
	static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;
	private static final long WINDOW_MS = 60_000;
	private static final int MAX_TRACKED = 200_000;

	private record Limit(String name, String prefix, int perMinute) {}

	private static final Limit API = new Limit("api", "/api/", 300);
	private static final List<Limit> LIMITS = List.of(
			new Limit("staff_sign_in", "/api/v1/admin/auth/", 10),
			new Limit("sign_in", "/api/v1/auth/", 20),
			new Limit("ask", "/api/v1/ask", 20),
			new Limit("guide", "/api/v1/guide", 40),
			new Limit("write", "/api/v1/reports", 5),
			new Limit("write", "/api/v1/grievances", 5),
			new Limit("write", "/api/v1/outcomes", 10),
			new Limit("match", "/api/v1/match", 60),
			API);

	private static final class Window {
		final long start;
		final AtomicInteger hits = new AtomicInteger();

		Window(long start) {
			this.start = start;
		}
	}

	private final Map<String, Window> windows = new ConcurrentHashMap<>();
	private final ClientAddress addresses;
	private final WebProperties properties;
	private final MeterRegistry meters;

	public RateLimitFilter(ClientAddress addresses, WebProperties properties, MeterRegistry meters) {
		this.addresses = addresses;
		this.properties = properties;
		this.meters = meters;
	}

	@Override
	public int getOrder() {
		return ORDER;
	}

	private static Limit limitFor(String path, String method) {
		for (Limit l : LIMITS) {
			if (path.startsWith(l.prefix())) {
				// Reading a grievance's status or the outcome codes is not a write.
				return "write".equals(l.name()) && "GET".equals(method) ? API : l;
			}
		}
		return null;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		// Telegram's servers share a few addresses, and the webhook has its own secret.
		return !properties.rateLimitEnabled() || request.getRequestURI().endsWith("/telegram/webhook");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Limit limit = limitFor(request.getRequestURI().substring(request.getContextPath().length()), request.getMethod());
		if (limit == null) {
			chain.doFilter(request, response);
			return;
		}
		long now = System.currentTimeMillis();
		long start = now - now % WINDOW_MS;
		if (windows.size() > MAX_TRACKED) {
			// A flood of forged sources: forget and carry on rather than run out of memory.
			log.warn("Rate limit table full, clearing it");
			windows.clear();
		}
		Window w = windows.compute(limit.name() + '|' + addresses.of(request), (k, old) -> old == null || old.start != start ? new Window(start) : old);
		if (w.hits.incrementAndGet() > limit.perMinute()) {
			meters.counter("jansathi.rate_limited", "limit", limit.name()).increment();
			response.setStatus(429);
			response.setHeader("Retry-After", String.valueOf(Math.max(1, (start + WINDOW_MS - now) / 1000)));
			response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
			response.getWriter().write("{\"status\":429,\"detail\":\"Too many requests. Wait a minute and try again.\"}");
			return;
		}
		chain.doFilter(request, response);
	}

	@Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
	void forgetOldWindows() {
		long current = System.currentTimeMillis() / WINDOW_MS * WINDOW_MS;
		windows.values().removeIf(w -> w.start < current);
	}
}
