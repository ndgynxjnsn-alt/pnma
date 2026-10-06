package com.example.springboot.profile;

import java.util.Optional;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiFunction;

/**
 * Holds the active version of the search profile.
 *
 * <p>
 * Scans hold the read lock for their whole duration, so any number of them can use the
 * current matcher concurrently. Replacing the matcher takes the write lock, which waits until
 * every in-flight scan has released the old matcher; after the swap no reader can still see
 * it, so it is closed (outside the lock). The lock is fair so a steady stream of scans cannot
 * starve an update.
 */
final class ProfileHolder implements AutoCloseable {

	private final ReadWriteLock lock = new ReentrantReadWriteLock(true);

	// Guarded by lock.
	private CompiledProfile current;

	private long version;

	/**
	 * Makes {@code next} the active version and closes the previous one.
	 * @return the new version number
	 */
	long install(CompiledProfile next) {
		CompiledProfile previous;
		long installed;
		this.lock.writeLock().lock();
		try {
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
		return installed;
	}

	/**
	 * Applies {@code action} to the active version while holding the read lock.
	 * @return the action's result, or empty if no version has been installed
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

	/** Closes the active version once no scan uses it. */
	@Override
	public void close() {
		CompiledProfile previous;
		this.lock.writeLock().lock();
		try {
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

}
