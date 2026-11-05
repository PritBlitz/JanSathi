package org.jansathi.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Hands the caller to admin controllers, and writes every admin API call to the insert-only {@code
 * admin_audit}: who, the route, the record and the status. Reads count too: the grievance queue
 * holds people's words. It sits on the route, so a new admin endpoint is audited without asking.
 */
@Configuration
public class AdminWebConfig implements WebMvcConfigurer, HandlerMethodArgumentResolver, HandlerInterceptor {

	private static final Logger log = LoggerFactory.getLogger(AdminWebConfig.class);

	private final JdbcClient jdbc;

	public AdminWebConfig(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		resolvers.add(this);
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(this).addPathPatterns("/api/v1/admin/**").excludePathPatterns("/api/v1/admin/auth/**");
	}

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.getParameterType() == AdminPrincipal.class;
	}

	@Override
	public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest request, WebDataBinderFactory binders) {
		Object p = request.getNativeRequest(HttpServletRequest.class).getAttribute(AdminPrincipal.ATTRIBUTE);
		if (p == null) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to the admin screens first");
		}
		return p;
	}

	@Override
	public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
		if (request.getAttribute(AdminPrincipal.ATTRIBUTE) instanceof AdminPrincipal actor) {
			Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
			Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
			Object target = vars instanceof Map<?, ?> m ? m.get("id") : null;
			try {
				write(actor, request.getMethod() + " " + route, target == null ? null : target.toString(), ex == null ? response.getStatus() : 500);
			} catch (RuntimeException e) {
				log.error("Admin audit write failed: {}", e.getClass().getName());
			}
		}
	}

	private void write(AdminPrincipal actor, String action, String target, int status) {
		jdbc.sql("""
				INSERT INTO admin_audit (id, actor_id, actor_label, action, target, status, created_at)
				VALUES (:id, :a, :l, :act, :t, :s, :now)
				""")
				.param("id", UUID.randomUUID().toString()).param("a", actor.id()).param("l", actor.label()).param("act", action)
				.param("t", target).param("s", status).param("now", OffsetDateTime.now(ZoneOffset.UTC)).update();
	}
}
