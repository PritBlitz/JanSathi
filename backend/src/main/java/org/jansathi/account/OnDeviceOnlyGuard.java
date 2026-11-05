package org.jansathi.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jansathi.web.OnDeviceOnlyException;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Saathi keeps a conversation on the server, so someone in on-device-only mode (under 18, PRIVACY
 * §6) can't use it. The anonymous assistant ({@code /ask}) keeps nothing and stays open to them.
 */
@Configuration
public class OnDeviceOnlyGuard implements HandlerInterceptor, WebMvcConfigurer {

	private final Accounts accounts;

	public OnDeviceOnlyGuard(Accounts accounts) {
		this.accounts = accounts;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(this).addPathPatterns("/api/v1/guide/**");
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		accounts.bySession(CurrentUserResolver.bearer(request)).ifPresent(u -> {
			if (accounts.onDeviceOnly(u.id())) {
				throw new OnDeviceOnlyException();
			}
		});
		return true;
	}
}
