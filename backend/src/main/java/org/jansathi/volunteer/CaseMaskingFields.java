package org.jansathi.volunteer;

import java.util.List;

/** The masked field names, readable outside this package (for tests and the API docs). */
public final class CaseMaskingFields {

	private CaseMaskingFields() {}

	public static List<String> names() {
		return CaseMasking.SENSITIVE_FIELDS;
	}
}
