package com.example.springboot.stress;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.LongStream;

import com.example.springboot.stress.ApiClient.Profile;
import com.example.springboot.stress.ApiClient.Saved;
import com.example.springboot.stress.ApiClient.Scanned;
import com.example.springboot.stress.RuleSets.Rule;
import com.example.springboot.stress.RuleSets.RuleSet;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hammers the HTTP API with rule sets that are expensive for Hyperscan while profiles are
 * swapped, to surface races between scans and database replacement.
 *
 * <p>
 * Scans are validated after the fact: every save response maps a version to the rule set it
 * installed, and every scan response must report exactly the ids that rule set produces for
 * the scanned sample. A scan that ran against the wrong, a half-installed or a freed database
 * cannot pass that check. Each client issues its requests sequentially, so the versions it
 * observes must never go backwards.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "scan.max-content-bytes=" + HyperscanApiStressTests.MAX_CONTENT_BYTES)
class HyperscanApiStressTests {

	static final int MAX_CONTENT_BYTES = 1024 * 1024;

	private static final Duration MAX_LOAD_DURATION = Duration.ofMinutes(2);

	private static final List<String> SAMPLE_NAMES = List.copyOf(RuleSets.SAMPLES.keySet());

	@LocalServerPort
	private int port;

	private ApiClient api;

	private ExecutorService clients;

	/** Which rule set each version installed, filled from save responses. */
	private final Map<Long, RuleSet> installed = new ConcurrentHashMap<>();

	@BeforeEach
	void setUp() {
		this.api = new ApiClient(this.port);
		this.clients = Executors.newVirtualThreadPerTaskExecutor();
	}

	@AfterEach
	void tearDown() {
		this.clients.close();
	}

	@Test
	void everyRuleSetReportsTheExpectedIdsForEverySample() {
		// Guard against a vacuous oracle: the generated rule sets must match something.
		assertThat(RuleSets.expected(RuleSets.LITERAL_FREE, "alnum-with-hits")).hasSizeGreaterThan(5);
		assertThat(RuleSets.expected(RuleSets.LITERAL_FREE, "alnum-no-hits")).isEmpty();
		assertThat(RuleSets.expected(RuleSets.BOUNDED_REPEATS, "repeat-gaps")).hasSizeGreaterThan(5)
			.hasSizeLessThan(RuleSets.BOUNDED_REPEATS.rules().size());

		SoftAssertions softly = new SoftAssertions();
		for (RuleSet ruleSet : RuleSets.ALL) {
			Saved saved = save(ruleSet);
			RuleSets.SAMPLES.forEach((sample, content) -> {
				Scanned scanned = this.api.scan(content);
				softly.assertThat(scanned.status()).as("%s / %s status", ruleSet.name(), sample).isEqualTo(200);
				softly.assertThat(scanned.version()).as("%s / %s version", ruleSet.name(), sample)
					.isEqualTo(saved.version());
				softly.assertThat(scanned.matches())
					.as("%s / %s matches", ruleSet.name(), sample)
					.isEqualTo(RuleSets.expected(ruleSet, sample));
			});
		}
		softly.assertAll();
	}

	@Test
	void scansNeverMixUpDatabaseVersionsWhileProfilesAreSwapped() throws Exception {
		long previous = save(RuleSets.CURATED).version();
		ConcurrentLinkedQueue<Profile> profileReads = new ConcurrentLinkedQueue<>();
		List<Observation> observations;
		// Mostly the samples that take literal-free rules milliseconds to scan, so that swapped-out
		// databases usually still have scans running on them.
		List<String> samples = new ArrayList<>(SAMPLE_NAMES);
		for (int i = 0; i < 10; i++) {
			samples.addAll(List.of("alnum-no-hits", "alnum-with-hits"));
		}
		try (Load load = new Load(48, 60, samples)) {
			load.run(() -> profileReads.add(this.api.profile()));
			// Cycle through rule sets that produce different ids for the same content, with
			// rejected saves in between that must not change anything.
			List<RuleSet> cycle = List.of(RuleSets.LITERAL_FREE, RuleSets.LITERAL_FREE_NEGATED, RuleSets.CURATED,
					RuleSets.LITERAL_FREE, RuleSets.CURATED_NEGATED, RuleSets.LITERAL_FREE_NEGATED);
			for (int swap = 0; swap < 30; swap++) {
				Saved saved = save(cycle.get(swap % cycle.size()));
				assertThat(saved.version()).as("versions of consecutive saves").isEqualTo(previous + 1);
				previous = saved.version();
				if (swap % 3 == 0) {
					assertThat(this.api.save(List.of(new Rule(1, "(a)\\1", Set.of()))).status()).isEqualTo(400);
				}
			}
			observations = load.stop();
		}

		assertConsistent(observations);
		assertThat(observations.stream().map(Observation::version).distinct().count())
			.as("distinct versions seen by scans")
			.isGreaterThan(5);
		assertThat(profileReads).isNotEmpty().allSatisfy((read) -> {
			assertThat(read.status()).isEqualTo(200);
			assertThat(read.ids()).as("ids of version %d", read.version())
				.isEqualTo(this.installed.get(read.version()).rules().stream().map(Rule::id).toList());
		});
	}

	@Test
	void slowCompileDoesNotBlockScansOfTheCurrentVersion() throws Exception {
		long before = save(RuleSets.CURATED).version();
		long after;
		long compileMillis;
		List<Observation> observations;
		try (Load load = new Load(16, 0)) {
			long start = System.nanoTime();
			after = save(RuleSets.BOUNDED_REPEATS).version();
			compileMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
			observations = load.stop();
		}

		assertThat(compileMillis).as("bounded repeats must be slow to compile for this test to mean anything")
			.isGreaterThan(300);
		assertConsistent(observations);
		long servedDuringCompile = observations.stream().filter((o) -> o.version() == before).count();
		assertThat(servedDuringCompile).as("scans answered by the old version during a %d ms compile", compileMillis)
			.isGreaterThan(50);
		assertThat(scanOnce("repeat-gaps")).isEqualTo(new Scanned(200, after,
				RuleSets.expected(RuleSets.BOUNDED_REPEATS, "repeat-gaps")));
	}

	@Test
	void concurrentSavesGetDistinctConsecutiveVersions() throws Exception {
		long first = save(RuleSets.CURATED).version();
		List<Observation> observations;
		try (Load load = new Load(16, 20)) {
			List<RuleSet> choices = List.of(RuleSets.CURATED, RuleSets.CURATED_NEGATED, RuleSets.LITERAL_FREE);
			CountDownLatch start = new CountDownLatch(1);
			List<Future<?>> savers = new ArrayList<>();
			for (int saver = 0; saver < 8; saver++) {
				Random random = new Random(saver);
				savers.add(this.clients.submit(() -> {
					start.await();
					for (int i = 0; i < 3; i++) {
						save(choices.get(random.nextInt(choices.size())));
					}
					return null;
				}));
			}
			start.countDown();
			awaitAll(savers);
			observations = load.stop();
		}

		assertThat(this.installed.keySet()).as("versions handed out")
			.containsAll(LongStream.rangeClosed(first, first + 24).boxed().toList());
		Profile current = this.api.profile();
		assertThat(current.version()).isEqualTo(first + 24);
		assertThat(current.ids()).isEqualTo(this.installed.get(current.version()).rules().stream().map(Rule::id).toList());
		assertConsistent(observations);
	}

	@Test
	void rejectsPatternsHyperscanCannotHandleWithoutDisturbingScans() throws Exception {
		long version = save(RuleSets.CURATED).version();
		Map<Rule, String> unsupported = Map.of(
				new Rule(-11, "(a)\\1", Set.of()), "Back-references are unsupported",
				new Rule(-12, "foo(?=bar)", Set.of()), "Zero-width assertions are not supported",
				new Rule(-13, "a*", Set.of()), "Pattern matches empty buffer",
				new Rule(-14, "broken(", Set.of()), "parenthes",
				new Rule(-15, "\\p{L}{256}", Set.of("UTF8", "UCP")), "Pattern too large",
				new Rule(-16, "([a-z]|bc*d|xy?z){1000,5000}", Set.of()), "Pattern is too large",
				// Spends seconds compiling before giving up.
				new Rule(-17, "\\w{64}", Set.of("UTF8", "UCP")), "Pattern is too large");
		List<Observation> observations;
		try (Load load = new Load(16, 20)) {
			List<Future<?>> saves = new ArrayList<>();
			unsupported.forEach((bad, message) -> saves.add(this.clients.submit(() -> {
				// Bury the bad rule in the middle of an otherwise valid, large rule set.
				List<Rule> rules = new ArrayList<>(RuleSets.LITERAL_FREE.rules());
				rules.add(rules.size() / 2, bad);
				Saved saved = this.api.save(rules);
				assertThat(saved.status()).as(bad.expression()).isEqualTo(400);
				assertThat(saved.detail()).as(bad.expression())
					.startsWith("Pattern " + bad.id() + " is invalid")
					.contains(message);
			})));
			awaitAll(saves);
			observations = load.stop();
		}

		assertThat(this.api.profile().version()).isEqualTo(version);
		assertConsistent(observations);
		assertThat(observations).allMatch((o) -> o.version() == version);
	}

	@Test
	void handlesEdgeCaseContentConcurrently() throws Exception {
		long version = save(RuleSets.CURATED).version();
		byte[] log = RuleSets.SAMPLES.get("log");
		byte[] atLimit = new byte[MAX_CONTENT_BYTES];
		java.util.Arrays.fill(atLimit, (byte) 'x');
		System.arraycopy(log, 0, atLimit, MAX_CONTENT_BYTES - log.length, log.length);
		byte[] overLimit = new byte[MAX_CONTENT_BYTES + 1];
		Map<String, byte[]> contents = Map.of("empty", new byte[0], "nul-bytes", RuleSets.SAMPLES.get("nul-bytes"),
				"at-limit", atLimit, "over-limit", overLimit, "unicode", RuleSets.SAMPLES.get("unicode"));
		Map<String, Scanned> expected = Map.of(
				"empty", new Scanned(200, version, List.of()),
				"nul-bytes", new Scanned(200, version, RuleSets.expected(RuleSets.CURATED, "nul-bytes")),
				// The filler before the log sample changes nothing the curated rules care about.
				"at-limit", new Scanned(200, version, RuleSets.expected(RuleSets.CURATED, "log")),
				"over-limit", new Scanned(413, -1, List.of()),
				"unicode", new Scanned(200, version, RuleSets.expected(RuleSets.CURATED, "unicode")));

		List<String> names = List.copyOf(contents.keySet());
		List<Future<?>> requests = new ArrayList<>();
		for (int i = 0; i < 200; i++) {
			String name = names.get(i % names.size());
			requests.add(this.clients.submit(() -> {
				assertThat(this.api.scan(contents.get(name))).as(name).isEqualTo(expected.get(name));
			}));
		}
		awaitAll(requests);
	}

	@Test
	void queuesFarMoreExpensiveScansThanThereAreThreads() throws Exception {
		long version = save(RuleSets.LITERAL_FREE).version();
		List<String> heavy = List.of("alnum-no-hits", "alnum-with-hits");
		List<Future<?>> requests = new ArrayList<>();
		for (int i = 0; i < 400; i++) {
			String sample = heavy.get(i % 2);
			requests.add(this.clients.submit(() -> {
				assertThat(scanOnce(sample)).as(sample)
					.isEqualTo(new Scanned(200, version, RuleSets.expected(RuleSets.LITERAL_FREE, sample)));
			}));
		}
		awaitAll(requests);
	}

	private record Observation(int client, int sequence, String sample, long version, List<Long> matches) {
	}

	/**
	 * Background load: clients that each scan random samples one after another until stopped
	 * (but at least {@code minScans} times). Closing it always stops the clients, so a failing
	 * test cannot leave them running; they also give up after {@link #MAX_LOAD_DURATION}.
	 */
	private final class Load implements AutoCloseable {

		private final AtomicBoolean running = new AtomicBoolean(true);

		private final long deadline = System.nanoTime() + MAX_LOAD_DURATION.toNanos();

		private final Queue<Observation> observations = new ConcurrentLinkedQueue<>();

		private final List<Future<?>> tasks = new ArrayList<>();

		private final List<String> samples;

		Load(int clients, int minScans) {
			this(clients, minScans, SAMPLE_NAMES);
		}

		/** @param samples names of the samples to pick from at random, repeated to weight them */
		Load(int clients, int minScans, List<String> samples) {
			this.samples = List.copyOf(samples);
			for (int client = 0; client < clients; client++) {
				int id = client;
				this.tasks.add(HyperscanApiStressTests.this.clients.submit(() -> scan(id, minScans)));
			}
		}

		/** Additionally runs {@code action} in a loop for as long as the load runs. */
		void run(Runnable action) {
			this.tasks.add(HyperscanApiStressTests.this.clients.submit(() -> {
				while (keepGoing()) {
					action.run();
				}
			}));
		}

		/** Stops the clients and returns what they observed, failing if any client failed. */
		List<Observation> stop() throws Exception {
			this.running.set(false);
			awaitAll(this.tasks);
			return List.copyOf(this.observations);
		}

		@Override
		public void close() {
			this.running.set(false);
		}

		private void scan(int client, int minScans) {
			Random random = new Random(client);
			for (int sequence = 0; sequence < minScans || keepGoing(); sequence++) {
				String sample = this.samples.get(random.nextInt(this.samples.size()));
				Scanned scanned = HyperscanApiStressTests.this.api.scan(RuleSets.SAMPLES.get(sample));
				assertThat(scanned.status()).as("scan of %s", sample).isEqualTo(200);
				this.observations.add(new Observation(client, sequence, sample, scanned.version(), scanned.matches()));
			}
		}

		private boolean keepGoing() {
			return this.running.get() && System.nanoTime() < this.deadline;
		}

	}

	private void assertConsistent(List<Observation> observations) {
		assertThat(observations).isNotEmpty();
		SoftAssertions softly = new SoftAssertions();
		Map<Integer, Long> lastVersion = new java.util.HashMap<>();
		observations.stream()
			.sorted(java.util.Comparator.comparingInt(Observation::client).thenComparingInt(Observation::sequence))
			.forEach((o) -> {
				RuleSet ruleSet = this.installed.get(o.version());
				softly.assertThat(ruleSet).as("rule set of version %d", o.version()).isNotNull();
				if (ruleSet != null) {
					softly.assertThat(o.matches())
						.as("client %d scan %d: %s with %s (version %d)", o.client(), o.sequence(), o.sample(),
								ruleSet.name(), o.version())
						.isEqualTo(RuleSets.expected(ruleSet, o.sample()));
				}
				Long previous = lastVersion.put(o.client(), o.version());
				if (previous != null) {
					softly.assertThat(o.version())
						.as("client %d scan %d went back from version %d", o.client(), o.sequence(), previous)
						.isGreaterThanOrEqualTo(previous);
				}
			});
		softly.assertAll();
	}

	private Saved save(RuleSet ruleSet) {
		Saved saved = this.api.save(ruleSet.rules());
		assertThat(saved.status()).as("save %s: %s", ruleSet.name(), saved.detail()).isEqualTo(200);
		this.installed.put(saved.version(), ruleSet);
		return saved;
	}

	private Scanned scanOnce(String sample) {
		return this.api.scan(RuleSets.SAMPLES.get(sample));
	}

	private static void awaitAll(List<Future<?>> tasks) throws Exception {
		for (Future<?> task : tasks) {
			task.get(5, TimeUnit.MINUTES);
		}
	}

}
