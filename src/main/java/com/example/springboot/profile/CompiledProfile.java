package com.example.springboot.profile;

import java.util.Arrays;
import java.util.List;

/** The patterns of a profile together with the matcher compiled from them. */
final class CompiledProfile implements AutoCloseable {

	private final List<PatternDefinition> patterns;

	private final PatternMatcher matcher;

	CompiledProfile(List<PatternDefinition> patterns, PatternMatcher matcher) {
		this.patterns = List.copyOf(patterns);
		this.matcher = matcher;
	}

	List<PatternDefinition> patterns() {
		return this.patterns;
	}

	/** Returns the ids of the matching patterns in ascending order. */
	List<Long> scan(byte[] content) {
		return Arrays.stream(this.matcher.scan(content))
			.mapToLong((index) -> this.patterns.get(index).id())
			.sorted()
			.boxed()
			.toList();
	}

	@Override
	public void close() {
		this.matcher.close();
	}

}
