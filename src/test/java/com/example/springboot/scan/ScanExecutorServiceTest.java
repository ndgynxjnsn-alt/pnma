package com.example.springboot.scan;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScanExecutorServiceTest {

	@Test
	void defaultConcurrencyIsCoresCappedAtFive() {
		int cores = Runtime.getRuntime().availableProcessors();
		assertThat(ScanExecutorService.defaultConcurrency()).isEqualTo(Math.min(cores, 5)).isBetween(1, 5);
	}

	@Test
	void neverRunsMoreScansThanItsConcurrencyLimit() {
		AtomicInteger running = new AtomicInteger();
		AtomicInteger peak = new AtomicInteger();
		try (ScanExecutorService executor = new ScanExecutorService(3)) {
			List<CompletableFuture<Void>> scans = new ArrayList<>();
			for (int i = 0; i < 30; i++) {
				scans.add(executor.submitScan(() -> {
					peak.accumulateAndGet(running.incrementAndGet(), Math::max);
					sleep(10);
					running.decrementAndGet();
					return null;
				}));
			}
			CompletableFuture.allOf(scans.toArray(CompletableFuture[]::new)).join();
		}
		assertThat(peak).hasValue(3);
	}

	@Test
	void queuesScansUntilAThreadIsFree() throws InterruptedException {
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (ScanExecutorService executor = new ScanExecutorService(1)) {
			CompletableFuture<String> running = executor.submitScan(() -> {
				started.countDown();
				await(release);
				return "first";
			});
			assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
			List<CompletableFuture<Integer>> queued = new ArrayList<>();
			for (int i = 0; i < 1_000; i++) {
				int value = i;
				queued.add(executor.submitScan(() -> value));
			}
			Thread.sleep(100);
			assertThat(queued).noneMatch(CompletableFuture::isDone);
			assertThat(executor.getQueue()).hasSize(1_000);

			release.countDown();
			assertThat(running).succeedsWithin(5, TimeUnit.SECONDS).isEqualTo("first");
			for (int i = 0; i < queued.size(); i++) {
				assertThat(queued.get(i)).succeedsWithin(5, TimeUnit.SECONDS).isEqualTo(i);
			}
		}
	}

	@Test
	void runsScansOnDedicatedThreads() {
		try (ScanExecutorService executor = new ScanExecutorService(1)) {
			assertThat(executor.submitScan(() -> Thread.currentThread().getName()))
				.succeedsWithin(5, TimeUnit.SECONDS)
				.asString()
				.startsWith("hs-scan-");
		}
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await(5, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}
