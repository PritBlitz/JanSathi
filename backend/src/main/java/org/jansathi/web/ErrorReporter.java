package org.jansathi.web;

/** Where unexpected errors go. A provider behind an interface (docs/ARCHITECTURE.md §6). */
public interface ErrorReporter {

	/**
	 * @param error what went wrong; messages are scrubbed before anything leaves the server
	 * @param where a route pattern or job name, never a path with an id in it
	 */
	void report(Throwable error, String where);
}
