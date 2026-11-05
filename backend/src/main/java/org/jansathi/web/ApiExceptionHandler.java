package org.jansathi.web;

import jakarta.servlet.http.HttpServletRequest;
import org.jansathi.rules.RuleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * RFC 9457 problem+json for every error. Messages never include request bodies, so profile
 * answers never end up in responses or logs.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	private final ErrorReporter errors;

	public ApiExceptionHandler(ErrorReporter errors) {
		this.errors = errors;
	}

	@ExceptionHandler(NotFoundException.class)
	ProblemDetail notFound(NotFoundException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
	}

	@ExceptionHandler(BadRequestException.class)
	ProblemDetail badRequest(BadRequestException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
	}

	/** A broken rule in content is a content bug (CI should catch it), never the user's fault. */
	@ExceptionHandler(RuleException.class)
	ProblemDetail rule(RuleException e) {
		log.error("Invalid rule in content", e);
		errors.report(e, "rules");
		return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "A scheme rule could not be evaluated");
	}

	/** The person said they are under 18: the app keeps working on the device (PRIVACY §6). */
	@ExceptionHandler(OnDeviceOnlyException.class)
	ProblemDetail onDeviceOnly(OnDeviceOnlyException e) {
		ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
		p.setProperty("code", OnDeviceOnlyException.CODE);
		return p;
	}

	/** A provider did not take the message; nothing the person did. */
	@ExceptionHandler(ProviderUnavailableException.class)
	ProblemDetail provider(ProviderUnavailableException e) {
		log.warn("Provider failed: {}", Scrub.clean(e.getMessage()));
		return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "That service is not responding. Try again in a minute.");
	}

	/** Anything unexpected. The message may hold anything, so only the generic text goes back. */
	@ExceptionHandler(Exception.class)
	ProblemDetail unexpected(Exception e, HttpServletRequest request) {
		Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
		String where = route == null ? "unknown" : request.getMethod() + " " + route;
		log.error("Unexpected error in {}: {}", where, e.getClass().getName());
		errors.report(e, where);
		return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong on our side");
	}
}
