package com.example.springboot.profile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
		this.service.save("p", List.of(pattern(9001, "foo"), pattern(42, "bar"), pattern(7, "nope"),
				new PatternDefinition(-3, "BAZ", java.util.Set.of(PatternFlag.CASELESS))));

		assertThat(scan("p", "bar baz foo")).isEqualTo(new ScanResult("p", 1, List.of(-3L, 42L, 9001L)));
		assertThat(scan("p", "nothing")).isEqualTo(new ScanResult("p", 1, List.of()));
	}

	@Test
	void savingAgainReplacesTheProfileWithANewVersion() {
		this.service.save("p", List.of(pattern(1, "foo")));
		ProfileInfo saved = this.service.save("p", List.of(pattern(2, "bar")));

		assertThat(saved.version()).isEqualTo(2);
		assertThat(this.service.get("p")).isEqualTo(saved);
		assertThat(scan("p", "foo bar").matches()).containsExactly(2L);
		assertThat(this.compiler.compiled().getFirst().isClosed()).isTrue();
	}

	@Test
	void profilesAreIndependent() {
		this.service.save("a", List.of(pattern(1, "x")));
		this.service.save("b", List.of(pattern(2, "x")));

		assertThat(scan("a", "x").matches()).containsExactly(1L);
		assertThat(scan("b", "x").matches()).containsExactly(2L);
	}

	@Test
	void scanRunningDuringSaveFinishesOnTheOldVersion() throws Exception {
		this.service.save("p", List.of(pattern(1, "foo")));
		CountDownLatch scanning = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		this.compiler.onScan(() -> {
			scanning.countDown();
			await(release);
		});
		CompletableFuture<ScanResult> slowScan = this.service.scan("p", bytes("foo"));
		assertThat(scanning.await(5, TimeUnit.SECONDS)).isTrue();

		CompletableFuture<ProfileInfo> save = CompletableFuture
			.supplyAsync(() -> this.service.save("p", List.of(pattern(2, "foo"))));
		Thread.sleep(200);
		assertThat(save).isNotDone();
		assertThat(this.compiler.compiled().getFirst().isClosed()).isFalse();

		this.compiler.onScan(() -> {
		});
		release.countDown();
		assertThat(slowScan).succeedsWithin(5, TimeUnit.SECONDS).isEqualTo(new ScanResult("p", 1, List.of(1L)));
		assertThat(save).succeedsWithin(5, TimeUnit.SECONDS).extracting(ProfileInfo::version).isEqualTo(2L);
		assertThat(this.compiler.compiled().getFirst().isClosed()).isTrue();
		assertThat(scan("p", "foo")).isEqualTo(new ScanResult("p", 2, List.of(2L)));
	}

	@Test
	void concurrentScansAndSavesNeverUseAClosedMatcher() {
		this.service.save("p", List.of(pattern(1, "foo")));
		List<CompletableFuture<ScanResult>> scans = new ArrayList<>();
		for (int i = 0; i < 200; i++) {
			scans.add(this.service.scan("p", bytes("foo")));
			if (i % 10 == 0) {
				this.service.save("p", List.of(pattern(1, "foo")));
			}
		}
		// A scan that hit a closed matcher would fail with IllegalStateException.
		assertThat(scans).allSatisfy((scan) -> assertThat(scan.join().matches()).containsExactly(1L));
	}

	@Test
	void deletedProfilesCannotBeScannedAndAreClosed() {
		this.service.save("p", List.of(pattern(1, "foo")));

		assertThat(this.service.delete("p")).isTrue();
		assertThat(this.service.delete("p")).isFalse();
		assertThat(this.compiler.compiled().getFirst().isClosed()).isTrue();
		assertThat(this.service.scan("p", bytes("foo"))).failsWithin(5, TimeUnit.SECONDS)
			.withThrowableThat()
			.havingCause()
			.isInstanceOf(ProfileNotFoundException.class);
		assertThatExceptionOfType(ProfileNotFoundException.class).isThrownBy(() -> this.service.get("p"));
	}

	@Test
	void profileCanBeRecreatedAfterDelete() {
		this.service.save("p", List.of(pattern(1, "foo")));
		this.service.delete("p");
		this.service.save("p", List.of(pattern(2, "foo")));

		assertThat(scan("p", "foo")).isEqualTo(new ScanResult("p", 1, List.of(2L)));
	}

	@Test
	void destroyClosesAllProfiles() {
		this.service.save("a", List.of(pattern(1, "x")));
		this.service.save("b", List.of(pattern(1, "x")));

		this.service.destroy();

		assertThat(this.compiler.compiled()).allMatch(RegexMatcherCompiler.RegexMatcher::isClosed);
	}

	@Test
	void reportsWhichPatternFailedToCompile() {
		assertThatExceptionOfType(InvalidProfileException.class)
			.isThrownBy(() -> this.service.save("p", List.of(pattern(10, "ok"), pattern(20, "broken("))))
			.withMessageStartingWith("Pattern 20 is invalid");
	}

	@Test
	void rejectsInvalidProfiles() {
		assertInvalid("bad name!", List.of(pattern(1, "x")), "Profile name");
		assertInvalid("p", List.of(), "at least one pattern");
		assertInvalid("p", List.of(pattern(1, "x"), pattern(1, "y")), "used more than once");
		assertInvalid("p", List.of(pattern(1, "")), "has no expression");
		assertThat(this.compiler.compiled()).isEmpty();
	}

	private void assertInvalid(String name, List<PatternDefinition> patterns, String message) {
		assertThatExceptionOfType(InvalidProfileException.class).isThrownBy(() -> this.service.save(name, patterns))
			.withMessageContaining(message);
	}

	private ScanResult scan(String profile, String content) {
		return this.service.scan(profile, bytes(content)).join();
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
