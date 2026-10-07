package com.example.springboot.stress;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.example.springboot.stress.RuleSets.Rule;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Minimal blocking client for the HTTP API; safe to share between threads. */
final class ApiClient {

	record Saved(int status, long version, String detail) {
	}

	record Scanned(int status, long version, List<Long> matches) {
	}

	record Profile(int status, long version, List<Long> ids) {
	}

	/** {@code version} is absent from error responses. */
	private record ProfileBody(Long version, List<Rule> patterns, String detail) {
	}

	private record ScanBody(long version, List<Long> matches) {
	}

	private static final Duration TIMEOUT = Duration.ofSeconds(60);

	private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

	private final JsonMapper json = JsonMapper.builder()
		.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
		.build();

	private final URI base;

	ApiClient(int port) {
		this.base = URI.create("http://localhost:" + port + "/api/");
	}

	Saved save(List<Rule> rules) {
		HttpRequest request = request("profile").header("Content-Type", "application/json")
			.PUT(HttpRequest.BodyPublishers.ofString(this.json.writeValueAsString(Map.of("patterns", rules))))
			.build();
		HttpResponse<String> response = send(request);
		ProfileBody body = this.json.readValue(response.body(), ProfileBody.class);
		return new Saved(response.statusCode(), versionOf(body), body.detail());
	}

	Scanned scan(byte[] content) {
		HttpRequest request = request("scan").header("Content-Type", "application/octet-stream")
			.POST(HttpRequest.BodyPublishers.ofByteArray(content))
			.build();
		HttpResponse<String> response = send(request);
		if (response.statusCode() != 200) {
			return new Scanned(response.statusCode(), -1, List.of());
		}
		ScanBody body = this.json.readValue(response.body(), ScanBody.class);
		return new Scanned(response.statusCode(), body.version(), body.matches());
	}

	Profile profile() {
		HttpResponse<String> response = send(request("profile").GET().build());
		ProfileBody body = this.json.readValue(response.body(), ProfileBody.class);
		List<Long> ids = (body.patterns() != null) ? body.patterns().stream().map(Rule::id).toList() : List.of();
		return new Profile(response.statusCode(), versionOf(body), ids);
	}

	private static long versionOf(ProfileBody body) {
		return (body.version() != null) ? body.version() : -1;
	}

	private HttpRequest.Builder request(String path) {
		return HttpRequest.newBuilder(this.base.resolve(path)).timeout(TIMEOUT);
	}

	private HttpResponse<String> send(HttpRequest request) {
		try {
			return this.http.send(request, HttpResponse.BodyHandlers.ofString());
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

}
