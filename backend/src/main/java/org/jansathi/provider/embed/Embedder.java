package org.jansathi.provider.embed;

import java.util.List;

/**
 * Turns text into vectors for semantic retrieval (docs/ARCHITECTURE.md §6). Off by default: the
 * assistant then uses keyword retrieval alone. Only official passages and the question, with
 * personal numbers already removed, are ever sent.
 */
public interface Embedder {

	enum Kind {
		QUERY,
		PASSAGE
	}

	boolean enabled();

	/** Stored with cached vectors: a new model means new vectors. */
	String modelId();

	/**
	 * @return one vector per text, in order
	 * @throws EmbedException when the service fails; retrieval falls back to keywords
	 */
	List<float[]> embed(List<String> texts, Kind kind);

	class EmbedException extends RuntimeException {
		public EmbedException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	Embedder DISABLED = new Embedder() {
		@Override
		public boolean enabled() {
			return false;
		}

		@Override
		public String modelId() {
			return null;
		}

		@Override
		public List<float[]> embed(List<String> texts, Kind kind) {
			throw new EmbedException("No embedder is configured", null);
		}
	};
}
