package org.jansathi.rules;

/**
 * A profile value known only as a range, e.g. an age band of 60–79. Rules are evaluated at both
 * ends of every band they read (see docs/RULES_ENGINE.md §3.1).
 */
public record Band(double min, double max) {

	public Band {
		if (min > max) {
			throw new IllegalArgumentException("Band min " + min + " is greater than max " + max);
		}
	}
}
