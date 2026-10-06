package com.example.springboot.hyperscan;

/**
 * Raised when libhs rejects a pattern. {@code patternIndex} is the index of the offending
 * pattern in the compiled list, or -1 if the error is not tied to a single pattern.
 */
public class PatternCompileException extends HyperscanException {

	private final int patternIndex;

	public PatternCompileException(String message, int patternIndex) {
		super(message);
		this.patternIndex = patternIndex;
	}

	public int getPatternIndex() {
		return this.patternIndex;
	}

}
