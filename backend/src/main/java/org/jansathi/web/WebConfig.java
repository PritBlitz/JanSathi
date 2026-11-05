package org.jansathi.web;

import java.time.Duration;
import org.jansathi.JansathiProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.ObjectMapper;

/** CORS for the PWA's origins, caching for the bundled PWA, and error reporting. */
@Configuration
@EnableConfigurationProperties(WebProperties.class)
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
					.allowedMethods("GET", "POST", "PUT", "DELETE")
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

	/** Unexpected errors go nowhere unless a DSN is set. */
	@Bean
	ErrorReporter errorReporter(WebProperties web, ObjectMapper json) {
		return web.sentryDsn() == null ? (error, where) -> {} : new SentryErrorReporter(web.sentryDsn(), web.environment(), json);
	}
}
