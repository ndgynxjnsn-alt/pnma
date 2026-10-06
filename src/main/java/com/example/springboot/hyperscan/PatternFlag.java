package com.example.springboot.hyperscan;

/** Per-pattern compile flags (HS_FLAG_* in hs_compile.h). */
public enum PatternFlag {

	CASELESS(1), DOTALL(2), MULTILINE(4), ALLOWEMPTY(16), UTF8(32), UCP(64);

	/** Always set: we only report which patterns matched, not every match offset. */
	static final int HS_FLAG_SINGLEMATCH = 8;

	final int bit;

	PatternFlag(int bit) {
		this.bit = bit;
	}

}
