package com.example.springboot.profile;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.example.springboot.hyperscan.PatternCompileException;
import com.example.springboot.scan.ScanExecutorService;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

@Service
public class ProfileService implements DisposableBean {

	private final ProfileHolder holder = new ProfileHolder();

	private final MatcherCompiler compiler;

	private final ScanExecutorService executor;

	public ProfileService(MatcherCompiler compiler, ScanExecutorService executor) {
		this.compiler = compiler;
		this.executor = executor;
	}

	/**
	 * Compiles {@code patterns} and atomically replaces the active database with it. Scans
	 * already running against the previous version finish on that version.
	 */
	public ProfileInfo save(List<PatternDefinition> patterns) {
		validate(patterns);
		// Compile before touching the holder: this is the slow part and must not block scans.
		CompiledProfile compiled = new CompiledProfile(patterns, compile(patterns));
		long version = this.holder.install(compiled);
		return new ProfileInfo(version, compiled.patterns());
	}

	public ProfileInfo get() {
		return this.holder.read((profile, version) -> new ProfileInfo(version, profile.patterns()))
			.orElseThrow(NoProfileException::new);
	}

	/**
	 * Scans {@code content} with the active database on the scan executor. Fails with
	 * {@link NoProfileException} if no profile has been saved yet.
	 */
	public CompletableFuture<ScanResult> scan(byte[] content) {
		return this.executor.submitScan(() -> this.holder
			.read((profile, version) -> new ScanResult(version, profile.scan(content)))
			.orElseThrow(NoProfileException::new));
	}

	@Override
	public void destroy() {
		this.holder.close();
	}

	private PatternMatcher compile(List<PatternDefinition> patterns) {
		try {
			return this.compiler.compile(patterns);
		}
		catch (PatternCompileException ex) {
			int index = ex.getPatternIndex();
			String prefix = (index >= 0 && index < patterns.size())
					? "Pattern " + patterns.get(index).id() + " is invalid: " : "Patterns are invalid: ";
			throw new InvalidProfileException(prefix + ex.getMessage());
		}
	}

	private static void validate(List<PatternDefinition> patterns) {
		if (patterns == null || patterns.isEmpty()) {
			throw new InvalidProfileException("A profile needs at least one pattern");
		}
		Set<Long> ids = new HashSet<>();
		for (PatternDefinition pattern : patterns) {
			if (pattern.expression() == null || pattern.expression().isEmpty()) {
				throw new InvalidProfileException("Pattern " + pattern.id() + " has no expression");
			}
			if (!ids.add(pattern.id())) {
				throw new InvalidProfileException("Pattern id " + pattern.id() + " is used more than once");
			}
		}
	}

}
