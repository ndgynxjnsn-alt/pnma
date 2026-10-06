package com.example.springboot.profile;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiFunction;

/**
 * Holds the active version of one profile.
 *
 * <p>
 * Scans hold the read lock for their whole duration, so any number of them can use the
 * current matcher concurrently. Replacing the matcher takes the write lock, which waits until
 * every in-flight scan has released the old matcher; after the swap no reader can still see
 * it, so it is closed (outside the lock). The lock is fair so a steady stream of scans cannot
 * starve an update.
 */
final class ProfileHolder {

	private final ReadWriteLock lock = new ReentrantReadWriteLock(true);

	// Guarded by lock.
	private CompiledProfile current;

	private long version;

	private boolean retired;

	/**
	 * Makes {@code next} the active version and closes the previous one.
	 * @return the new version number, or empty if this holder was retired (the profile was
	 * deleted concurrently) and {@code next} was not installed
	 */
	OptionalLong install(CompiledProfile next) {
		CompiledProfile previous;
		long installed;
		this.lock.writeLock().lock();
		try {
			if (this.retired) {
				return OptionalLong.empty();
			}
			previous = this.current;
			this.current = next;
			installed = ++this.version;
		}
		finally {
			this.lock.writeLock().unlock();
		}
		if (previous != null) {
			previous.close();
		}
		return OptionalLong.of(installed);
	}

	/** Permanently empties this holder, closing the active version once no scan uses it. */
	void retire() {
		CompiledProfile previous;
		this.lock.writeLock().lock();
		try {
			this.retired = true;
			previous = this.current;
			this.current = null;
		}
		finally {
			this.lock.writeLock().unlock();
		}
		if (previous != null) {
			previous.close();
		}
	}

	/**
	 * Applies {@code action} to the active version while holding the read lock.
	 * @return the action's result, or empty if there is no active version
	 */
	<T> Optional<T> read(BiFunction<CompiledProfile, Long, T> action) {
		this.lock.readLock().lock();
		try {
			return (this.current != null) ? Optional.of(action.apply(this.current, this.version)) : Optional.empty();
		}
		finally {
			this.lock.readLock().unlock();
		}
	}

}
