package com.example.springboot.web;

import java.util.List;
import java.util.Set;

import com.example.springboot.hyperscan.PatternFlag;
import com.example.springboot.profile.InvalidProfileException;
import com.example.springboot.profile.PatternDefinition;

public record SaveProfileRequest(List<Pattern> patterns) {

	public record Pattern(Long id, String expression, Set<PatternFlag> flags) {
	}

	List<PatternDefinition> toDefinitions() {
		if (this.patterns == null) {
			return List.of();
		}
		return this.patterns.stream().map((pattern) -> {
			if (pattern == null || pattern.id() == null) {
				throw new InvalidProfileException("Every pattern needs a numeric id");
			}
			return new PatternDefinition(pattern.id(), pattern.expression(), pattern.flags());
		}).toList();
	}

}
