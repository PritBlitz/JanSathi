package org.jansathi.admin;

/**
 * Who is calling the admin API. A controller parameter of this type receives them.
 *
 * @param id a staff account id, or {@code bootstrap-token}
 * @param label how they appear in the admin audit and on closed cases
 * @param role {@code admin} or {@code reviewer}
 */
public record AdminPrincipal(String id, String label, String role) {

	public static final String ATTRIBUTE = "jansathi.admin";
	public static final AdminPrincipal BOOTSTRAP = new AdminPrincipal("bootstrap-token", "Bootstrap token", "admin");

	public boolean isAdmin() {
		return "admin".equals(role);
	}
}
