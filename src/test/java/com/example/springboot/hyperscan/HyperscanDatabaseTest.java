package com.example.springboot.hyperscan;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.example.springboot.hyperscan.HyperscanDatabase.Expression;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class HyperscanDatabaseTest {

	@Test
	void reportsEachMatchingExpressionOnce() {
		try (HyperscanDatabase db = compile("foo", "ba[rz]", "qux\\d+")) {
			assertThat(db.scan(bytes("foo bar foo baz"))).containsExactly(0, 1);
			assertThat(db.scan(bytes("qux42"))).containsExactly(2);
			assertThat(db.scan(bytes("nothing here"))).isEmpty();
		}
	}

	@Test
	void scansEmptyContent() {
		try (HyperscanDatabase db = compile("foo")) {
			assertThat(db.scan(new byte[0])).isEmpty();
		}
	}

	@Test
	void appliesFlags() {
		List<Expression> expressions = List.of(new Expression("hello", Set.of()),
				new Expression("hello", Set.of(PatternFlag.CASELESS)),
				new Expression("^world$", Set.of(PatternFlag.MULTILINE)),
				new Expression("ü+", Set.of(PatternFlag.UTF8)));
		try (HyperscanDatabase db = HyperscanDatabase.compile(expressions)) {
			assertThat(db.scan(bytes("HELLO\nworld\n"))).containsExactly(1, 2);
			assertThat(db.scan(bytes("grüße"))).containsExactly(3);
		}
	}

	@Test
	void reportsTheInvalidExpression() {
		assertThatExceptionOfType(PatternCompileException.class).isThrownBy(() -> compile("ok", "broken(", "ok2"))
			.satisfies((ex) -> assertThat(ex.getPatternIndex()).isEqualTo(1))
			.withMessageContaining("parenthes");
	}

	@Test
	void rejectsNulInExpression() {
		assertThatExceptionOfType(PatternCompileException.class).isThrownBy(() -> compile("a\0b"))
			.satisfies((ex) -> assertThat(ex.getPatternIndex()).isZero());
	}

	@Test
	void rejectsEmptyExpressionList() {
		assertThatIllegalArgumentException().isThrownBy(() -> HyperscanDatabase.compile(List.of()));
	}

	@Test
	void refusesToScanAfterClose() {
		HyperscanDatabase db = compile("foo");
		db.close();
		db.close();
		assertThatIllegalStateException().isThrownBy(() -> db.scan(bytes("foo")));
	}

	@Test
	void supportsConcurrentScans() throws Exception {
		try (HyperscanDatabase db = compile("alpha", "beta", "gamma");
				ExecutorService pool = Executors.newFixedThreadPool(8)) {
			List<Callable<int[]>> scans = new ArrayList<>();
			for (int i = 0; i < 2_000; i++) {
				String content = switch (i % 3) {
					case 0 -> "alpha";
					case 1 -> "beta gamma";
					default -> "none";
				};
				scans.add(() -> db.scan(bytes(content)));
			}
			List<Future<int[]>> results = pool.invokeAll(scans);
			for (int i = 0; i < results.size(); i++) {
				int[] expected = switch (i % 3) {
					case 0 -> new int[] { 0 };
					case 1 -> new int[] { 1, 2 };
					default -> new int[0];
				};
				assertThat(results.get(i).get()).containsExactly(expected);
			}
		}
	}

	private static HyperscanDatabase compile(String... patterns) {
		return HyperscanDatabase.compile(List.of(patterns).stream().map((p) -> new Expression(p, Set.of())).toList());
	}

	private static byte[] bytes(String content) {
		return content.getBytes(StandardCharsets.UTF_8);
	}

}
