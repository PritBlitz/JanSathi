package org.jansathi.account;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * A controller parameter of type {@link Accounts.User} receives the signed-in person, from the
 * {@code Authorization: Bearer <session token>} header; without a valid session the request is 401.
 */
@Configuration
public class CurrentUserResolver implements HandlerMethodArgumentResolver, WebMvcConfigurer {

	private final Accounts accounts;

	public CurrentUserResolver(Accounts accounts) {
		this.accounts = accounts;
	}

	public static String bearer(HttpServletRequest request) {
		String h = request.getHeader("Authorization");
		return h != null && h.startsWith("Bearer ") ? h.substring(7).strip() : null;
	}

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		resolvers.add(this);
	}

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.getParameterType() == Accounts.User.class;
	}

	@Override
	public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest request, WebDataBinderFactory binders) {
		return accounts.bySession(bearer(request.getNativeRequest(HttpServletRequest.class)))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in first"));
	}
}
