package com.example.springboot;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import com.example.springboot.profile.ProfileInfo;
import com.example.springboot.profile.ScanResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/** End-to-end over HTTP against the real libhs. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
class HyperscanApiApplicationTests {

	@Autowired
	private RestTestClient client;

	@Test
	void savesAProfileAndScansContent() {
		long first = save(Map.of("id", 3141592653L, "expression", "secret-[0-9]{4}"),
				Map.of("id", 27182818L, "expression", "PASSWORD", "flags", List.of("CASELESS")),
				Map.of("id", 1L, "expression", "never"));

		assertThat(scan("my password is secret-1234"))
			.isEqualTo(new ScanResult(first, List.of(27182818L, 3141592653L)));

		long second = save(Map.of("id", 5L, "expression", "password"));
		assertThat(second).isEqualTo(first + 1);
		assertThat(scan("my password is secret-1234")).isEqualTo(new ScanResult(second, List.of(5L)));
	}

	@Test
	void rejectsExpressionsHyperscanCannotCompile() {
		this.client.put()
			.uri("/api/profile")
			.contentType(MediaType.APPLICATION_JSON)
			.body(Map.of("patterns", List.of(Map.of("id", 99, "expression", "(unclosed"))))
			.exchange()
			.expectStatus()
			.isBadRequest()
			.expectBody()
			.jsonPath("$.detail")
			.value(String.class, (detail) -> assertThat(detail).startsWith("Pattern 99 is invalid"));
	}

	@Test
	void handlesManyConcurrentScansWhileTheProfileChanges() {
		save(Map.of("id", 1L, "expression", "needle"));
		List<CompletableFuture<ScanResult>> scans = IntStream.range(0, 100)
			.mapToObj((i) -> CompletableFuture.supplyAsync(() -> scan("hay needle hay")))
			.toList();
		IntStream.range(0, 10).forEach((i) -> save(Map.of("id", 1L, "expression", "needle")));

		assertThat(scans).allSatisfy((scan) -> assertThat(scan.join().matches()).containsExactly(1L));
	}

	/** @return the version of the saved profile */
	@SafeVarargs
	private long save(Map<String, Object>... patterns) {
		return this.client.put()
			.uri("/api/profile")
			.contentType(MediaType.APPLICATION_JSON)
			.body(Map.of("patterns", List.of(patterns)))
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(ProfileInfo.class)
			.returnResult()
			.getResponseBody()
			.version();
	}

	private ScanResult scan(String content) {
		return this.client.post()
			.uri("/api/scan")
			.contentType(MediaType.TEXT_PLAIN)
			.body(content)
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(ScanResult.class)
			.returnResult()
			.getResponseBody();
	}

}
