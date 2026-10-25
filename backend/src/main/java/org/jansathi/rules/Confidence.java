package org.jansathi.rules;

/** How well a rule is supported by its source. Ordered from strongest to weakest. */
public enum Confidence {
	CONFIRMED,
	GUIDANCE,
	UNCERTAIN;

	public static Confidence weakest(Confidence a, Confidence b) {
		return a.ordinal() >= b.ordinal() ? a : b;
	}
}
