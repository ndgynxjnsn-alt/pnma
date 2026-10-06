package com.example.springboot.profile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.IntStream;

import com.example.springboot.hyperscan.PatternCompileException;
import com.example.springboot.hyperscan.PatternFlag;

/**
 * Test double for the Hyperscan compiler based on {@link java.util.regex}. Its matchers fail
 * any scan that overlaps with {@link PatternMatcher#close()}, so use-after-free bugs in the
 * locking surface as test failures instead of native crashes.
 */
public class RegexMatcherCompiler implements MatcherCompiler {

	private final List<RegexMatcher> compiled = new CopyOnWriteArrayList<>();

	private volatile Runnable scanHook = () -> {
	};

	/** Runs {@code hook} inside every subsequent scan, e.g. to block it on a latch. */
	public void onScan(Runnable hook) {
		this.scanHook = hook;
	}

	public List<RegexMatcher> compiled() {
		return this.compiled;
	}

	@Override
	public PatternMatcher compile(List<PatternDefinition> patterns) {
		List<Pattern> regexes = new ArrayList<>();
		for (int i = 0; i < patterns.size(); i++) {
			PatternDefinition pattern = patterns.get(i);
			try {
				regexes.add(Pattern.compile(pattern.expression(), javaFlags(pattern)));
			}
			catch (PatternSyntaxException ex) {
				throw new PatternCompileException(ex.getDescription(), i);
			}
		}
		RegexMatcher matcher = new RegexMatcher(regexes);
		this.compiled.add(matcher);
		return matcher;
	}

	private static int javaFlags(PatternDefinition pattern) {
		int flags = 0;
		if (pattern.flags().contains(PatternFlag.CASELESS)) {
			flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
		}
		if (pattern.flags().contains(PatternFlag.DOTALL)) {
			flags |= Pattern.DOTALL;
		}
		if (pattern.flags().contains(PatternFlag.MULTILINE)) {
			flags |= Pattern.MULTILINE;
		}
		return flags;
	}

	public final class RegexMatcher implements PatternMatcher {

		private final List<Pattern> regexes;

		private volatile boolean closed;

		private RegexMatcher(List<Pattern> regexes) {
			this.regexes = regexes;
		}

		public boolean isClosed() {
			return this.closed;
		}

		@Override
		public int[] scan(byte[] content) {
			assertOpen();
			RegexMatcherCompiler.this.scanHook.run();
			String text = new String(content, StandardCharsets.UTF_8);
			int[] matches = IntStream.range(0, this.regexes.size())
				.filter((i) -> this.regexes.get(i).matcher(text).find())
				.toArray();
			assertOpen();
			return matches;
		}

		@Override
		public void close() {
			this.closed = true;
		}

		private void assertOpen() {
			if (this.closed) {
				throw new IllegalStateException("Matcher used after close");
			}
		}

	}

}
