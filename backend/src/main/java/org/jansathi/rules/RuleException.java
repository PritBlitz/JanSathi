package org.jansathi.rules;

/** A rule uses an operator or shape outside the §9.2 subset. */
public class RuleException extends RuntimeException {

	public RuleException(String message) {
		super(message);
	}
}
