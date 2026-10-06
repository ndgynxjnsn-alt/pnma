package com.example.springboot.web;

import com.example.springboot.profile.ProfileService;
import com.example.springboot.profile.RegexMatcherCompiler;
import com.example.springboot.scan.ScanConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;

@WebMvcTest(controllers = ProfileController.class, properties = "scan.max-content-bytes=32B")
@Import({ ProfileService.class, ScanConfiguration.class, RegexMatcherCompiler.class })
class ProfileControllerTest {

	@Autowired
	private MockMvcTester mvc;

	@Autowired
	private ProfileService profiles;

	@AfterEach
	void deleteProfiles() {
		this.profiles.destroy();
	}

	@Test
	void savesAProfile() {
		assertThat(save("animals", """
				{"patterns": [
				  {"id": 4711, "expression": "cat"},
				  {"id": 815, "expression": "DOG", "flags": ["CASELESS"]}
				]}"""))
			.hasStatusOk()
			.bodyJson()
			.isLenientlyEqualTo("""
					{"name": "animals", "version": 1, "patterns": [
					  {"id": 4711, "expression": "cat", "flags": []},
					  {"id": 815, "expression": "DOG", "flags": ["CASELESS"]}
					]}""");

		assertThat(this.mvc.get().uri("/api/profiles/animals")).hasStatusOk()
			.bodyJson()
			.extractingPath("$.patterns.length()")
			.isEqualTo(2);
	}

	@Test
	void scansTheRequestBodyAndReturnsMatchingIds() {
		givenProfile("animals", """
				{"patterns": [
				  {"id": 4711, "expression": "cat"},
				  {"id": 815, "expression": "DOG", "flags": ["CASELESS"]},
				  {"id": 1, "expression": "bird"}
				]}""");

		assertThat(scan("animals", "the dog chased the cat", MediaType.TEXT_PLAIN)).hasStatusOk()
			.bodyJson()
			.isStrictlyEqualTo("""
					{"profile": "animals", "version": 1, "matches": [815, 4711]}""");
		assertThat(scan("animals", "bird", MediaType.APPLICATION_OCTET_STREAM)).hasStatusOk()
			.bodyJson()
			.extractingPath("$.matches")
			.isEqualTo(java.util.List.of(1));
	}

	@Test
	void scanningAnUnknownProfileIsNotFound() {
		assertThat(scan("missing", "content", MediaType.TEXT_PLAIN)).hasStatus(HttpStatus.NOT_FOUND)
			.bodyJson()
			.extractingPath("$.detail")
			.isEqualTo("Search profile 'missing' does not exist");
	}

	@Test
	void rejectsContentAboveTheLimit() {
		givenProfile("p", """
				{"patterns": [{"id": 1, "expression": "x"}]}""");

		assertThat(scan("p", "x".repeat(33), MediaType.TEXT_PLAIN)).hasStatus(HttpStatus.CONTENT_TOO_LARGE);
		assertThat(scan("p", "x".repeat(32), MediaType.TEXT_PLAIN)).hasStatusOk();
	}

	@Test
	void rejectsInvalidProfiles() {
		assertThat(save("p", """
				{"patterns": [{"id": 1, "expression": "ok"}, {"id": 2, "expression": "broken("}]}"""))
			.hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.extractingPath("$.detail")
			.asString()
			.startsWith("Pattern 2 is invalid");
		assertThat(save("p", """
				{"patterns": [{"expression": "no id"}]}""")).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(save("p", """
				{"patterns": [{"id": 1, "expression": "x", "flags": ["NOPE"]}]}""")).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(save("p", """
				{"patterns": []}""")).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(this.mvc.get().uri("/api/profiles/p")).hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void deletesAProfile() {
		givenProfile("p", """
				{"patterns": [{"id": 1, "expression": "x"}]}""");

		assertThat(this.mvc.delete().uri("/api/profiles/p")).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(this.mvc.delete().uri("/api/profiles/p")).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(scan("p", "x", MediaType.TEXT_PLAIN)).hasStatus(HttpStatus.NOT_FOUND);
	}

	private void givenProfile(String name, String json) {
		assertThat(save(name, json)).hasStatusOk();
	}

	private MockMvcTester.MockMvcRequestBuilder save(String name, String json) {
		return this.mvc.put().uri("/api/profiles/{name}", name).contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private MockMvcTester.MockMvcRequestBuilder scan(String name, String content, MediaType type) {
		return this.mvc.post().uri("/api/profiles/{name}/scan", name).contentType(type).content(content);
	}

}
