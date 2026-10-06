package com.example.springboot.profile;

import java.util.List;

public interface MatcherCompiler {

	/**
	 * Compiles {@code patterns} into a matcher.
	 * @throws com.example.springboot.hyperscan.PatternCompileException if a pattern is invalid
	 */
	PatternMatcher compile(List<PatternDefinition> patterns);

}
