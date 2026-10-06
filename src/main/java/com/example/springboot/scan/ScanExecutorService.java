package com.example.springboot.scan;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Runs CPU-bound scans on a fixed number of platform threads, so at most
 * {@code maxConcurrency} scans are in progress at any time. Further scans wait in an
 * unbounded queue until a thread is free.
 */
public class ScanExecutorService extends ThreadPoolExecutor {

	public static final int MAX_DEFAULT_CONCURRENCY = 5;

	public ScanExecutorService(int maxConcurrency) {
		super(maxConcurrency, maxConcurrency, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
				Thread.ofPlatform().name("hs-scan-", 0).factory());
	}

	/** {@code min(available cores, 5)} */
	public static int defaultConcurrency() {
		return Math.min(Runtime.getRuntime().availableProcessors(), MAX_DEFAULT_CONCURRENCY);
	}

	/** Schedules {@code scan} on this executor; it waits in the queue while all threads are busy. */
	public <T> CompletableFuture<T> submitScan(Supplier<T> scan) {
		return CompletableFuture.supplyAsync(scan, this);
	}

}
