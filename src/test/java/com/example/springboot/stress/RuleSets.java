package com.example.springboot.stress;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * Rule sets that are hard on Hyperscan in different ways, the content they are scanned with,
 * and the ids each scan must report.
 *
 * <ul>
 * <li>{@link #CURATED}: one rule per tricky feature (anchors, bounded repeats with and without
 * DOTALL, ReDoS shapes, UTF-8 and Unicode properties, NUL bytes, ids beyond 2^53). Expected
 * results are written out by hand.</li>
 * <li>{@link #CURATED_NEGATED}: the same expressions with negated ids. Swapping between the two
 * makes any mix-up between database versions visible in the ids.</li>
 * <li>{@link #LITERAL_FREE}: 200 character-class-only rules without a literal to anchor on, so
 * Hyperscan must run its automata over every byte (~400x slower than literal rules).</li>
 * <li>{@link #LITERAL_FREE_NEGATED}: the same with negated ids, so that swapping between two
 * databases whose scans take milliseconds frees one while scans are still running on it.</li>
 * <li>{@link #BOUNDED_REPEATS}: overlapping {@code a.{N,M}c} rules, which take seconds to
 * compile.</li>
 * </ul>
 * The generated sets only use byte-oriented constructs on which {@link java.util.regex} agrees
 * with Hyperscan, so their expectations come from it as an independent oracle.
 */
final class RuleSets {

	record Rule(long id, String expression, Set<String> flags) {
	}

	record RuleSet(String name, List<Rule> rules) {

		RuleSet negated() {
			return new RuleSet(this.name + "-negated",
					this.rules.stream().map((rule) -> new Rule(-rule.id(), rule.expression(), rule.flags())).toList());
		}

	}

	static final RuleSet CURATED = new RuleSet("curated", List.of(
			new Rule(7340021987443L, "secret-[0-9]{4}\\b", Set.of()),
			new Rule(-5522013377L, "password\\s*[:=]\\s*\\S{8,}", Set.of("CASELESS")),
			// Not representable as a double: catches ids that lose precision in JSON handling.
			new Rule(9007199254740993L, "AKIA[0-9A-Z]{16}", Set.of()),
			new Rule(4242L, "^BEGIN", Set.of("MULTILINE")),
			new Rule(31337L, "END$", Set.of()),
			new Rule(1001L, "a.{100,200}z", Set.of("DOTALL")),
			new Rule(1002L, "a.{100,200}z", Set.of()),
			new Rule(2718281828L, "(a+)+$", Set.of()),
			new Rule(5000000001L, "\\b[0-9]{4}([- ][0-9]{4}){3}\\b", Set.of()),
			new Rule(8080L, "gr(ü|ue)(ß|ss)e", Set.of("UTF8", "CASELESS")),
			new Rule(6006L, "\\p{Han}{2}", Set.of("UTF8", "UCP")),
			new Rule(99L, "\\x00\\x01\\x00", Set.of()),
			new Rule(1234567L, "(?i)select\\s+.+\\s+from\\s+\\w+", Set.of()),
			new Rule(77L, "(cat|dog)s? (chase|eat)s? (cat|dog|bird)s?", Set.of()),
			new Rule(Long.MAX_VALUE, "xyzzy", Set.of())));

	static final RuleSet CURATED_NEGATED = CURATED.negated();

	static final RuleSet LITERAL_FREE = new RuleSet("literal-free", IntStream.range(0, 200)
		.mapToObj((i) -> new Rule(randomId(1, i),
				"[a-" + (char) ('a' + i % 10) + "][0-" + (i % 10) + "]{" + (2 + i % 7) + "}[a-j]{" + (3 + i % 5) + "}[k-z]",
				Set.of()))
		.toList());

	static final RuleSet BOUNDED_REPEATS = new RuleSet("bounded-repeats", IntStream.range(0, 30)
		.mapToObj((i) -> new Rule(randomId(2, i), "a.{" + (10 + i * 7) + "," + (300 + i * 20) + "}c", Set.of("DOTALL")))
		.toList());

	static final RuleSet LITERAL_FREE_NEGATED = LITERAL_FREE.negated();

	static final List<RuleSet> ALL = List.of(CURATED, CURATED_NEGATED, LITERAL_FREE, LITERAL_FREE_NEGATED,
			BOUNDED_REPEATS);

	/** Content samples by name. */
	static final Map<String, byte[]> SAMPLES = new LinkedHashMap<>();

	private static final Map<String, List<Long>> ORACLE_CACHE = new ConcurrentHashMap<>();

	/** Hand-checked expectations for {@link #CURATED}, ids in ascending order. */
	private static final Map<String, List<Long>> CURATED_EXPECTED = new LinkedHashMap<>();

	static {
		curated("config", "user=admin\npassword = hunter2hunter2\nkey=AKIAABCDEFGHIJKLMNOP\n", -5522013377L,
				9007199254740993L);
		curated("log", "BEGIN transaction\nSELECT name FROM users\nsecret-1234 used\nBEGIN again\nthe dogs chase cats END",
				77L, 4242L, 31337L, 1234567L, 7340021987443L);
		curated("bounded-repeat", "a" + "x".repeat(150) + "z", 1001L, 1002L);
		// Only DOTALL lets the dot cross the newline.
		curated("bounded-repeat-newline", "a" + "x".repeat(70) + "\n" + "x".repeat(70) + "z", 1001L);
		curated("bounded-repeat-too-far", "a" + "x".repeat(250) + "z");
		// Catastrophic for a backtracking engine, linear for Hyperscan.
		curated("redos", "b" + "a".repeat(50_000), 2718281828L);
		// The second number is preceded by a digit, so it has no word boundary.
		curated("card-number", "pay 4111 1111 1111 1111 now, not 41111 1111 1111 1111", 5000000001L);
		curated("unicode", "Grüße aus 東京, GRUESSE", 6006L, 8080L);
		curated("nul-bytes", "junk\0\u0001\0junk", 99L);
		curated("empty", "");
		curated("near-misses", "near misses: xyzzy, AKIA123, secret-123, password: short, 4111-1111-1111",
				Long.MAX_VALUE);
		// Without MULTILINE, $ still matches before a final newline.
		curated("end-before-final-newline", "the END\n", 31337L);
		curated("begin-not-at-line-start", "not at start: xBEGIN\n  BEGIN");
		curated("caseless-password", "PassWord:SuperSecretValue", -5522013377L);

		Random random = new Random(42);
		// Alphabet a-j0-9 never contains the [k-z] every literal-free rule ends with. Injected hits
		// end in k-y, never z, so the curated a.{100,200}z rules cannot match them either.
		SAMPLES.put("alnum-no-hits", (randomAlnum(random, 256 * 1024) + "!").getBytes(StandardCharsets.US_ASCII));
		StringBuilder withHits = new StringBuilder(randomAlnum(random, 256 * 1024));
		for (int i = 0; i < 40; i++) {
			int digits = 2 + random.nextInt(7);
			int letters = 3 + random.nextInt(5);
			String hit = "a" + "0".repeat(digits) + "abcdefg".substring(0, letters) + (char) ('k' + random.nextInt(15));
			withHits.insert(random.nextInt(withHits.length()), " " + hit + " ");
		}
		SAMPLES.put("alnum-with-hits", (withHits + "!").getBytes(StandardCharsets.US_ASCII));
		StringBuilder gaps = new StringBuilder();
		// a.{N,M}c also matches from one line's a to a later line's c. Up to the last line, every
		// such distance is at most ~130, and the last gap exceeds every M, so only the rules with
		// a small N match.
		for (int gap : new int[] { 5, 15, 40, 60, 900 }) {
			gaps.append('a').append("b".repeat(gap)).append("c\n");
		}
		SAMPLES.put("repeat-gaps", gaps.append('!').toString().getBytes(StandardCharsets.US_ASCII));
	}

	private RuleSets() {
	}

	/** The ids {@code ruleSet} must report for the named sample, in ascending order. */
	static List<Long> expected(RuleSet ruleSet, String sample) {
		if (ruleSet == CURATED) {
			// The generated samples contain nothing the curated rules match.
			return CURATED_EXPECTED.getOrDefault(sample, List.of());
		}
		if (ruleSet == CURATED_NEGATED) {
			return expected(CURATED, sample).stream().map((id) -> -id).sorted().toList();
		}
		if (ruleSet == LITERAL_FREE_NEGATED) {
			return expected(LITERAL_FREE, sample).stream().map((id) -> -id).sorted().toList();
		}
		return ORACLE_CACHE.computeIfAbsent(ruleSet.name() + "/" + sample, (key) -> oracle(ruleSet, SAMPLES.get(sample)));
	}

	private static List<Long> oracle(RuleSet ruleSet, byte[] content) {
		// Hyperscan works on bytes; ISO-8859-1 maps each byte to one char.
		String text = new String(content, StandardCharsets.ISO_8859_1);
		List<Long> ids = new ArrayList<>();
		for (Rule rule : ruleSet.rules()) {
			int flags = rule.flags().contains("DOTALL") ? Pattern.DOTALL : 0;
			if (Pattern.compile(rule.expression(), flags).matcher(text).find()) {
				ids.add(rule.id());
			}
		}
		return ids.stream().sorted().toList();
	}

	private static void curated(String name, String content, Long... expected) {
		SAMPLES.put(name, content.getBytes(StandardCharsets.UTF_8));
		CURATED_EXPECTED.put(name, List.of(expected));
	}

	private static String randomAlnum(Random random, int length) {
		String alphabet = "abcdefghij0123456789";
		StringBuilder text = new StringBuilder(length);
		for (int i = 0; i < length; i++) {
			text.append(alphabet.charAt(random.nextInt(alphabet.length())));
		}
		return text.toString();
	}

	private static long randomId(int salt, int index) {
		return new Random(salt * 1_000_003L + index).nextLong();
	}

}
