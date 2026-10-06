package com.example.springboot.profile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.example.springboot.hyperscan.PatternFlag;
import com.example.springboot.scan.ScanExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class ProfileServiceTest {

	private final RegexMatcherCompiler compiler = new RegexMatcherCompiler();

	private final ScanExecutorService executor = new ScanExecutorService(2);

	private final ProfileService service = new ProfileService(this.compiler, this.executor);

	@AfterEach
	void shutdown() {
		this.service.destroy();
		this.executor.close();
	}

	@Test
	void returnsTheCallerChosenIdsOfMatchingPatternsInOrder() {
		this.service.save(List.of(pattern(9001, "foo"), pattern(42, "bar"), pattern(7, "nope"),
				new PatternDefinition(-3, "BAZ", Set.of(PatternFlag.CASELESS))));

		assertThat(scan("bar baz foo")).isEqualTo(new ScanResult(1, List.of(-3L, 42L, 9001L)));
		assertThat(scan("nothing")).isEqualTo(new ScanResult(1, List.of()));
	}

	@Test
	void savingAgainReplacesTheProfileWithANewVersion() {
		this.service.save(List.of(pattern(1, "foo")));
		ProfileInfo saved = this.service.save(List.of(pattern(2, "bar")));

		assertThat(saved.version()).isEqualTo(2);
		assertThat(this.service.get()).isEqualTo(saved);
		assertThat(scan("foo bar").matches()).containsExactly(2L);
		assertThat(this.compiler.compiled().getFirst().isClosed()).isTrue();
	}

	@Test
	void failsWhileNoProfileHasBeenSaved() {
		assertThatExceptionOfType(NoProfileException.class).isThrownBy(this.service::get);
		assertThat(this.service.scan(bytes("foo"))).failsWithin(5, TimeUnit.SECONDS)
			.withThrowableThat()
			.havingCause()
			.isInstanceOf(NoProfileException.class);
	}

	@Test
	void scanRunningDuringSaveFinishesOnTheOldVersion() throws Exception {
		this.service.save(List.of(pattern(1, "foo")));
		CountDownLatch scanning = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		this.compiler.onScan(() -> {
			scanning.countDown();
			await(release);
		});
		CompletableFuture<ScanResult> slowScan = this.service.scan(bytes("foo"));
		assertThat(scanning.await(5, TimeUnit.SECONDS)).isTrue();

		CompletableFuture<ProfileInfo> save = CompletableFuture
			.supplyAsync(() -> this.service.save(List.of(pattern(2, "foo"))));
		Thread.sleep(200);
		assertThat(save).isNotDone();
		assertThat(this.compiler.compiled().getFirst().isClosed()).isFalse();

		this.compiler.onScan(() -> {
		});
		release.countDown();
		assertThat(slowScan).succeedsWithin(5, TimeUnit.SECONDS).isEqualTo(new ScanResult(1, List.of(1L)));
		assertThat(save).succeedsWithin(5, TimeUnit.SECONDS).extracting(ProfileInfo::version).isEqualTo(2L);
		assertThat(this.compiler.compiled().getFirst().isClosed()).isTrue();
		assertThat(scan("foo")).isEqualTo(new ScanResult(2, List.of(2L)));
	}

	@Test
	void concurrentScansAndSavesNeverUseAClosedMatcher() {
		this.service.save(List.of(pattern(1, "foo")));
		List<CompletableFuture<ScanResult>> scans = new ArrayList<>();
		for (int i = 0; i < 200; i++) {
			scans.add(this.service.scan(bytes("foo")));
			if (i % 10 == 0) {
				this.service.save(List.of(pattern(1, "foo")));
			}
		}
		// A scan that hit a closed matcher would fail with IllegalStateException.
		assertThat(scans).allSatisfy((scan) -> assertThat(scan.join().matches()).containsExactly(1L));
	}

	@Test
	void destroyClosesTheActiveDatabase() {
		this.service.save(List.of(pattern(1, "x")));

		this.service.destroy();

		assertThat(this.compiler.compiled()).allMatch(RegexMatcherCompiler.RegexMatcher::isClosed);
	}

	@Test
	void reportsWhichPatternFailedToCompile() {
		assertThatExceptionOfType(InvalidProfileException.class)
			.isThrownBy(() -> this.service.save(List.of(pattern(10, "ok"), pattern(20, "broken("))))
			.withMessageStartingWith("Pattern 20 is invalid");
	}

	@Test
	void invalidProfilesKeepTheActiveDatabase() {
		this.service.save(List.of(pattern(1, "foo")));

		assertInvalid(List.of(), "at least one pattern");
		assertInvalid(List.of(pattern(1, "x"), pattern(1, "y")), "used more than once");
		assertInvalid(List.of(pattern(1, "")), "has no expression");
		assertInvalid(List.of(pattern(1, "broken(")), "is invalid");
		assertThat(scan("foo")).isEqualTo(new ScanResult(1, List.of(1L)));
	}

	private void assertInvalid(List<PatternDefinition> patterns, String message) {
		assertThatExceptionOfType(InvalidProfileException.class).isThrownBy(() -> this.service.save(patterns))
			.withMessageContaining(message);
	}

	private ScanResult scan(String content) {
		return this.service.scan(bytes(content)).join();
	}

	private static PatternDefinition pattern(long id, String expression) {
		return new PatternDefinition(id, expression, null);
	}

	private static byte[] bytes(String content) {
		return content.getBytes(StandardCharsets.UTF_8);
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
