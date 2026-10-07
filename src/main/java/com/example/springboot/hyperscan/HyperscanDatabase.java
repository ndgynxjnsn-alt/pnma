package com.example.springboot.hyperscan;

import java.util.BitSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.StringArray;
import com.sun.jna.ptr.PointerByReference;

/**
 * A compiled block-mode Hyperscan database.
 *
 * <p>
 * {@link #scan} is thread-safe: every thread scans with its own scratch space, which is kept
 * in a thread-local and grown by {@code hs_alloc_scratch} whenever a database needs more than
 * the scratch currently provides, so one scratch serves all databases. Threads that scan
 * should call {@link #freeThreadScratch()} before they exit. {@link #close} must not run
 * concurrently with a scan; callers guarantee this (see {@code ProfileHolder}).
 */
public final class HyperscanDatabase implements AutoCloseable {

	/** A single expression to compile. The pattern id reported by {@link #scan} is its list index. */
	public record Expression(String pattern, Set<PatternFlag> flags) {

		public Expression {
			flags = Set.copyOf(flags);
		}

	}

	private static final ThreadLocal<ThreadState> THREAD_STATE = ThreadLocal.withInitial(ThreadState::new);

	// hs_scan invokes the callback synchronously on the calling thread, so it finds the scan's
	// match collector in the thread-local. The callback instance must stay strongly reachable
	// for JNA, hence the static field.
	private static final HyperscanLibrary.MatchEventHandler COLLECTOR = new MatchCollector();

	private final HyperscanLibrary lib;

	private final Pointer database;

	private final AtomicBoolean closed = new AtomicBoolean();

	private HyperscanDatabase(HyperscanLibrary lib, Pointer database) {
		this.lib = lib;
		this.database = database;
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
		return new HyperscanDatabase(lib, db.getValue());
	}

	/** Returns the sorted, distinct indices of all expressions that match {@code data}. */
	public int[] scan(byte[] data) {
		if (this.closed.get()) {
			throw new IllegalStateException("Database is closed");
		}
		ThreadState state = THREAD_STATE.get();
		// No-op unless this database needs a larger scratch than the thread has so far.
		check(this.lib.hs_alloc_scratch(this.database, state.scratch), "hs_alloc_scratch");
		state.matches.clear();
		try {
			check(this.lib.hs_scan(this.database, data, data.length, 0, state.scratch.getValue(), COLLECTOR, null),
					"hs_scan");
			return state.matches.stream().toArray();
		}
		finally {
			state.matches.clear();
		}
	}

	/** Frees the calling thread's scratch space, if it has one. */
	public static void freeThreadScratch() {
		ThreadState state = THREAD_STATE.get();
		THREAD_STATE.remove();
		Pointer scratch = state.scratch.getValue();
		if (scratch != null) {
			Hyperscan.lib().hs_free_scratch(scratch);
		}
	}

	@Override
	public void close() {
		if (this.closed.compareAndSet(false, true)) {
			this.lib.hs_free_database(this.database);
		}
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

	private static final class ThreadState {

		/** Starts out as NULL, which makes the first hs_alloc_scratch allocate a new scratch. */
		final PointerByReference scratch = new PointerByReference();

		final BitSet matches = new BitSet();

	}

	private static final class MatchCollector implements HyperscanLibrary.MatchEventHandler {

		@Override
		public int invoke(int id, long from, long to, int flags, Pointer context) {
			THREAD_STATE.get().matches.set(id);
			return 0;
		}

	}

}
