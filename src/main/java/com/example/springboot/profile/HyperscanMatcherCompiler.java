package com.example.springboot.profile;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.springboot.hyperscan.Hyperscan;
import com.example.springboot.hyperscan.HyperscanDatabase;
import org.springframework.stereotype.Component;

@Component
class HyperscanMatcherCompiler implements MatcherCompiler {

	private static final Logger logger = LoggerFactory.getLogger(HyperscanMatcherCompiler.class);

	HyperscanMatcherCompiler() {
		// Fails startup if libhs cannot be loaded.
		logger.info("Using Hyperscan {}", Hyperscan.version());
	}

	@Override
	public PatternMatcher compile(List<PatternDefinition> patterns) {
		List<HyperscanDatabase.Expression> expressions = patterns.stream()
			.map((pattern) -> new HyperscanDatabase.Expression(pattern.expression(), pattern.flags()))
			.toList();
		return new HyperscanMatcher(HyperscanDatabase.compile(expressions));
	}

	private record HyperscanMatcher(HyperscanDatabase database) implements PatternMatcher {

		@Override
		public int[] scan(byte[] content) {
			return this.database.scan(content);
		}

		@Override
		public void close() {
			this.database.close();
		}

	}

}
