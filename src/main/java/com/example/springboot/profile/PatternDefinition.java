package com.example.springboot.profile;

import java.util.Set;

import com.example.springboot.hyperscan.PatternFlag;

/** A regex of a search profile, identified by a caller-chosen numeric id. */
public record PatternDefinition(long id, String expression, Set<PatternFlag> flags) {

	public PatternDefinition {
		flags = (flags != null) ? Set.copyOf(flags) : Set.of();
	}

}
