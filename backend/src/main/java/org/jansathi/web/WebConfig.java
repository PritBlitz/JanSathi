package org.jansathi.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.jansathi.JansathiProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** CORS for the PWA's origins, caching for the bundled PWA, and baseline security headers. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

	private final JansathiProperties properties;

	public WebConfig(JansathiProperties properties) {
		this.properties = properties;
	}

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		if (!properties.corsOrigins().isEmpty()) {
			registry.addMapping("/api/**")
					.allowedOrigins(properties.corsOrigins().toArray(String[]::new))
					.allowedMethods("GET", "POST")
					.maxAge(3600);
		}
	}

	/** Vite gives built assets content-hashed names, so they never change and can be cached for a year. */
	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		registry.addResourceHandler("/assets/**")
				.addResourceLocations("classpath:/static/assets/")
				.setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
		registry.addResourceHandler("/fonts/**")
				.addResourceLocations("classpath:/static/fonts/")
				.setCacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic());
	}

	@Bean
	OncePerRequestFilter securityHeaders() {
		return new OncePerRequestFilter() {
			@Override
			protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
					throws ServletException, IOException {
				response.setHeader("X-Content-Type-Options", "nosniff");
				response.setHeader("Referrer-Policy", "no-referrer");
				response.setHeader("X-Frame-Options", "DENY");
				response.setHeader("Permissions-Policy", "camera=(), geolocation=(), microphone=()");
				chain.doFilter(request, response);
			}
		};
	}
}
