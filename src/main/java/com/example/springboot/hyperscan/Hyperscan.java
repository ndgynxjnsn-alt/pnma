package com.example.springboot.hyperscan;

import com.sun.jna.Native;

/**
 * Lazily loads libhs. The library name can be overridden with {@code -Dhyperscan.library=...};
 * by default the unversioned name is tried first, then the soname shipped by Ubuntu's libhyperscan5.
 */
public final class Hyperscan {

	private static final String[] DEFAULT_NAMES = { "hs", "libhs.so.5" };

	private Hyperscan() {
	}

	static HyperscanLibrary lib() {
		return Holder.LIB;
	}

	public static String version() {
		return lib().hs_version();
	}

	public static boolean isAvailable() {
		try {
			lib();
			return true;
		}
		catch (LinkageError ex) {
			return false;
		}
	}

	private static final class Holder {

		static final HyperscanLibrary LIB = load();

		private static HyperscanLibrary load() {
			String configured = System.getProperty("hyperscan.library");
			String[] names = (configured != null) ? new String[] { configured } : DEFAULT_NAMES;
			UnsatisfiedLinkError failure = null;
			for (String name : names) {
				try {
					return Native.load(name, HyperscanLibrary.class);
				}
				catch (UnsatisfiedLinkError ex) {
					if (failure == null) {
						failure = ex;
					}
					else {
						failure.addSuppressed(ex);
					}
				}
			}
			throw failure;
		}

	}

}
