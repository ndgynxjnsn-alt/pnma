package com.example.springboot.profile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileHolderTest {

	private final RegexMatcherCompiler compiler = new RegexMatcherCompiler();

	private final ProfileHolder holder = new ProfileHolder();

	@Test
	void isEmptyUntilAVersionIsInstalled() {
		assertThat(this.holder.read((profile, version) -> version)).isEmpty();
	}

	@Test
	void installingAVersionClosesThePreviousOne() {
		assertThat(this.holder.install(profile(1, "a"))).isEqualTo(1);
		assertThat(this.holder.install(profile(2, "b"))).isEqualTo(2);

		assertThat(this.compiler.compiled().get(0).isClosed()).isTrue();
		assertThat(this.compiler.compiled().get(1).isClosed()).isFalse();
		assertThat(this.holder.read((profile, version) -> profile.scan(bytes("b")))).contains(List.of(2L));
	}

	@Test
	void installWaitsForInFlightScansOfThePreviousVersion() throws Exception {
		this.holder.install(profile(1, "a"));
		CountDownLatch scanning = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CompletableFuture<List<Long>> scan = CompletableFuture
			.supplyAsync(() -> this.holder.read((profile, version) -> {
				scanning.countDown();
				await(release);
				return profile.scan(bytes("a"));
			}).orElseThrow());
		assertThat(scanning.await(5, TimeUnit.SECONDS)).isTrue();

		CompletableFuture<Long> install = CompletableFuture.supplyAsync(() -> this.holder.install(profile(2, "a")));
		Thread.sleep(200);
		assertThat(install).isNotDone();
		assertThat(this.compiler.compiled().get(0).isClosed()).isFalse();

		release.countDown();
		assertThat(scan).succeedsWithin(5, TimeUnit.SECONDS).isEqualTo(List.of(1L));
		assertThat(install).succeedsWithin(5, TimeUnit.SECONDS).isEqualTo(2L);
		assertThat(this.compiler.compiled().get(0).isClosed()).isTrue();
	}

	@Test
	void closingClosesTheActiveVersion() {
		this.holder.install(profile(1, "a"));
		this.holder.close();

		assertThat(this.compiler.compiled().get(0).isClosed()).isTrue();
		assertThat(this.holder.read((profile, version) -> version)).isEmpty();
	}

	private CompiledProfile profile(long id, String expression) {
		List<PatternDefinition> patterns = List.of(new PatternDefinition(id, expression, null));
		return new CompiledProfile(patterns, this.compiler.compile(patterns));
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
