package com.example.springboot.hyperscan;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.StringArray;
import com.sun.jna.ptr.PointerByReference;

/**
 * A compiled block-mode Hyperscan database plus a pool of scratch spaces for it.
 *
 * <p>
 * {@link #scan} is thread-safe: each concurrent scan borrows its own scratch space, which is
 * cloned from an otherwise unused prototype on demand, so the pool grows to the peak scan
 * concurrency. {@link #close} must not run concurrently with a scan; callers guarantee this
 * (see {@code ProfileHolder}).
 */
public final class HyperscanDatabase implements AutoCloseable {

	/** A single expression to compile. The pattern id reported by {@link #scan} is its list index. */
	public record Expression(String pattern, Set<PatternFlag> flags) {

		public Expression {
			flags = Set.copyOf(flags);
		}

	}

	// hs_scan invokes the callback synchronously on the calling thread, so a thread-local
	// collector is enough to route matches back to the right scan. The callback instance must
	// stay strongly reachable for JNA, hence the static field.
	private static final ThreadLocal<BitSet> MATCHES = ThreadLocal.withInitial(BitSet::new);

	private static final HyperscanLibrary.MatchEventHandler COLLECTOR = new MatchCollector();

	private final HyperscanLibrary lib;

	private final Pointer database;

	private final Pointer prototypeScratch;

	private final int patternCount;

	private final ConcurrentLinkedQueue<Pointer> idleScratch = new ConcurrentLinkedQueue<>();

	private final List<Pointer> allScratch = new ArrayList<>();

	private final AtomicBoolean closed = new AtomicBoolean();

	private HyperscanDatabase(HyperscanLibrary lib, Pointer database, Pointer prototypeScratch, int patternCount) {
		this.lib = lib;
		this.database = database;
		this.prototypeScratch = prototypeScratch;
		this.patternCount = patternCount;
	}

	public static HyperscanDatabase compile(List<Expression> expressions) {
		if (expressions.isEmpty()) {
			throw new IllegalArgumentException("At least one expression is required");
		}
		HyperscanLibrary lib = Hyperscan.lib();
		int count = expressions.size();
		String[] patterns = new String[count];
		int[] flags = new int[count];
		int[] ids = new int[count];
		for (int i = 0; i < count; i++) {
			Expression expression = expressions.get(i);
			if (expression.pattern().indexOf('\0') >= 0) {
				throw new PatternCompileException("Expression must not contain NUL characters", i);
			}
			patterns[i] = expression.pattern();
			flags[i] = PatternFlag.HS_FLAG_SINGLEMATCH;
			for (PatternFlag flag : expression.flags()) {
				flags[i] |= flag.bit;
			}
			ids[i] = i;
		}

		PointerByReference db = new PointerByReference();
		PointerByReference error = new PointerByReference();
		int rc = lib.hs_compile_multi(new StringArray(patterns, "UTF-8"), flags, ids, count,
				HyperscanLibrary.HS_MODE_BLOCK, null, db, error);
		if (rc == HyperscanLibrary.HS_COMPILER_ERROR) {
			throw toCompileException(lib, error.getValue());
		}
		check(rc, "hs_compile_multi");

		Pointer database = db.getValue();
		PointerByReference scratch = new PointerByReference();
		rc = lib.hs_alloc_scratch(database, scratch);
		if (rc != HyperscanLibrary.HS_SUCCESS) {
			lib.hs_free_database(database);
			check(rc, "hs_alloc_scratch");
		}
		return new HyperscanDatabase(lib, database, scratch.getValue(), count);
	}

	/** Returns the sorted, distinct indices of all expressions that match {@code data}. */
	public int[] scan(byte[] data) {
		if (this.closed.get()) {
			throw new IllegalStateException("Database is closed");
		}
		Pointer scratch = borrowScratch();
		BitSet matches = MATCHES.get();
		matches.clear();
		try {
			check(this.lib.hs_scan(this.database, data, data.length, 0, scratch, COLLECTOR, null), "hs_scan");
			return matches.stream().toArray();
		}
		finally {
			matches.clear();
			this.idleScratch.offer(scratch);
		}
	}

	public int patternCount() {
		return this.patternCount;
	}

	@Override
	public void close() {
		if (!this.closed.compareAndSet(false, true)) {
			return;
		}
		synchronized (this.allScratch) {
			this.allScratch.forEach(this.lib::hs_free_scratch);
			this.allScratch.clear();
		}
		this.idleScratch.clear();
		this.lib.hs_free_scratch(this.prototypeScratch);
		this.lib.hs_free_database(this.database);
	}

	private Pointer borrowScratch() {
		Pointer scratch = this.idleScratch.poll();
		if (scratch != null) {
			return scratch;
		}
		// The prototype is never scanned with, so cloning it cannot race with an in-flight scan.
		PointerByReference clone = new PointerByReference();
		check(this.lib.hs_clone_scratch(this.prototypeScratch, clone), "hs_clone_scratch");
		synchronized (this.allScratch) {
			this.allScratch.add(clone.getValue());
		}
		return clone.getValue();
	}

	private static PatternCompileException toCompileException(HyperscanLibrary lib, Pointer error) {
		try {
			// struct hs_compile_error { char *message; int expression; }
			String message = error.getPointer(0).getString(0, "UTF-8");
			int expression = error.getInt(Native.POINTER_SIZE);
			return new PatternCompileException(message, expression);
		}
		finally {
			lib.hs_free_compile_error(error);
		}
	}

	private static void check(int rc, String function) {
		if (rc != HyperscanLibrary.HS_SUCCESS) {
			throw new HyperscanException(function + " failed with error code " + rc);
		}
	}

	private static final class MatchCollector implements HyperscanLibrary.MatchEventHandler {

		@Override
		public int invoke(int id, long from, long to, int flags, Pointer context) {
			MATCHES.get().set(id);
			return 0;
		}

	}

}
