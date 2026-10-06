package com.example.springboot.hyperscan;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

/**
 * JNA mapping of the subset of the libhs C API (hs.h) used by this application.
 * All {@code unsigned int} parameters are mapped to Java {@code int}.
 */
interface HyperscanLibrary extends Library {

	int HS_SUCCESS = 0;
	int HS_COMPILER_ERROR = -4;

	int HS_MODE_BLOCK = 1;

	int hs_compile_multi(Pointer expressions, int[] flags, int[] ids, int elements, int mode, Pointer platform,
			PointerByReference db, PointerByReference error);

	int hs_free_compile_error(Pointer error);

	int hs_free_database(Pointer db);

	int hs_alloc_scratch(Pointer db, PointerByReference scratch);

	int hs_clone_scratch(Pointer src, PointerByReference dest);

	int hs_free_scratch(Pointer scratch);

	int hs_scan(Pointer db, byte[] data, int length, int flags, Pointer scratch, MatchEventHandler onEvent,
			Pointer context);

	String hs_version();

	/** {@code int (*match_event_handler)(unsigned int id, unsigned long long from, unsigned long long to, unsigned int flags, void *context)} */
	interface MatchEventHandler extends Callback {

		int invoke(int id, long from, long to, int flags, Pointer context);

	}

}
