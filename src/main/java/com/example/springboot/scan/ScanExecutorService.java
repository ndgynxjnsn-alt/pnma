package com.example.springboot.scan;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.example.springboot.hyperscan.HyperscanDatabase;

/**
 * Runs CPU-bound scans on a fixed number of platform threads, so at most
 * {@code maxConcurrency} scans are in progress at any time. Further scans wait in an
 * unbounded queue until a thread is free. Each thread frees its Hyperscan scratch space when
 * it exits.
 */
public class ScanExecutorService extends ThreadPoolExecutor {

	public ScanExecutorService(int maxConcurrency) {
		super(maxConcurrency, maxConcurrency, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
				freeingScratchOnExit(Thread.ofPlatform().name("hs-scan-", 0).factory()));
	}

	/** One scan thread per available core. */
	public static int defaultConcurrency() {
		return Runtime.getRuntime().availableProcessors();
	}

	/** Schedules {@code scan} on this executor; it waits in the queue while all threads are busy. */
	public <T> CompletableFuture<T> submitScan(Supplier<T> scan) {
		return CompletableFuture.supplyAsync(scan, this);
	}

	private static ThreadFactory freeingScratchOnExit(ThreadFactory threads) {
		return (worker) -> threads.newThread(() -> {
			try {
				worker.run();
			}
			finally {
				HyperscanDatabase.freeThreadScratch();
			}
		});
	}

}
