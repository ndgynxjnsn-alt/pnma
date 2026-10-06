package com.example.springboot.profile;

import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

import com.example.springboot.hyperscan.PatternCompileException;
import com.example.springboot.scan.ScanExecutorService;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

@Service
public class ProfileService implements DisposableBean {

	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

	private final ConcurrentMap<String, ProfileHolder> profiles = new ConcurrentHashMap<>();

	private final MatcherCompiler compiler;

	private final ScanExecutorService executor;

	public ProfileService(MatcherCompiler compiler, ScanExecutorService executor) {
		this.compiler = compiler;
		this.executor = executor;
	}

	/**
	 * Compiles {@code patterns} and atomically replaces the profile's active version with it.
	 * Scans already running against the previous version finish on that version.
	 */
	public ProfileInfo save(String name, List<PatternDefinition> patterns) {
		validate(name, patterns);
		// Compile before touching the holder: this is the slow part and must not block scans.
		CompiledProfile compiled = new CompiledProfile(patterns, compile(patterns));
		while (true) {
			ProfileHolder holder = this.profiles.computeIfAbsent(name, (key) -> new ProfileHolder());
			OptionalLong version = holder.install(compiled);
			if (version.isPresent()) {
				return new ProfileInfo(name, version.getAsLong(), compiled.patterns());
			}
			// The holder was deleted concurrently and is no longer mapped; retry with a fresh one.
		}
	}

	public ProfileInfo get(String name) {
		return holder(name).read((profile, version) -> new ProfileInfo(name, version, profile.patterns()))
			.orElseThrow(() -> new ProfileNotFoundException(name));
	}

	/** @return whether the profile existed */
	public boolean delete(String name) {
		ProfileHolder holder = this.profiles.remove(name);
		if (holder == null) {
			return false;
		}
		holder.retire();
		return true;
	}

	/**
	 * Scans {@code content} with the profile's current version on the scan executor. Fails with
	 * {@link ProfileNotFoundException} if the profile does not exist (or is deleted before the
	 * scan starts).
	 */
	public CompletableFuture<ScanResult> scan(String name, byte[] content) {
		if (!this.profiles.containsKey(name)) {
			return CompletableFuture.failedFuture(new ProfileNotFoundException(name));
		}
		return this.executor.submitScan(() -> holder(name)
			.read((profile, version) -> new ScanResult(name, version, profile.scan(content)))
			.orElseThrow(() -> new ProfileNotFoundException(name)));
	}

	@Override
	public void destroy() {
		this.profiles.keySet().forEach(this::delete);
	}

	private ProfileHolder holder(String name) {
		ProfileHolder holder = this.profiles.get(name);
		if (holder == null) {
			throw new ProfileNotFoundException(name);
		}
		return holder;
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

	private static void validate(String name, List<PatternDefinition> patterns) {
		if (name == null || !NAME.matcher(name).matches()) {
			throw new InvalidProfileException("Profile name must match " + NAME.pattern());
		}
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
