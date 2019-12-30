package saker.apiextract.api;

/**
 * Tri-state boolean enumeration that allows specifying that the default value should be used.
 */
public enum DefaultableBoolean {
	/**
	 * The boolean value representing <code>true</code>.
	 */
	TRUE,
	/**
	 * The boolean value representing <code>false</code>.
	 */
	FALSE,
	/**
	 * Value representing that the default behaviour should be used for the given setting.
	 */
	DEFAULT;
}
