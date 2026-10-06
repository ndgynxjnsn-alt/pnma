package com.example.springboot.profile;

/**
 * A compiled set of patterns. Implementations must allow concurrent {@link #scan} calls, but
 * {@link #close} is only called once no scan is running.
 */
public interface PatternMatcher extends AutoCloseable {

	/** Returns the sorted, distinct indices (in compile order) of the patterns matching {@code content}. */
	int[] scan(byte[] content);

	@Override
	void close();

}
