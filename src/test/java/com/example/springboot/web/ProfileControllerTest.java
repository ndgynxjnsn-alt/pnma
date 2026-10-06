package com.example.springboot.web;

import java.util.List;

import com.example.springboot.profile.ProfileService;
import com.example.springboot.profile.RegexMatcherCompiler;
import com.example.springboot.scan.ScanConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;

@WebMvcTest(controllers = ProfileController.class, properties = "scan.max-content-bytes=32B")
@Import({ ProfileService.class, ScanConfiguration.class, RegexMatcherCompiler.class })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ProfileControllerTest {

	@Autowired
	private MockMvcTester mvc;

	@Test
	void savesTheProfile() {
		assertThat(save("""
				{"patterns": [
				  {"id": 4711, "expression": "cat"},
				  {"id": 815, "expression": "DOG", "flags": ["CASELESS"]}
				]}"""))
			.hasStatusOk()
			.bodyJson()
			.isLenientlyEqualTo("""
					{"version": 1, "patterns": [
					  {"id": 4711, "expression": "cat", "flags": []},
					  {"id": 815, "expression": "DOG", "flags": ["CASELESS"]}
					]}""");

		assertThat(this.mvc.get().uri("/api/profile")).hasStatusOk()
			.bodyJson()
			.extractingPath("$.patterns.length()")
			.isEqualTo(2);
	}

	@Test
	void scansTheRequestBodyAndReturnsMatchingIds() {
		givenProfile("""
				{"patterns": [
				  {"id": 4711, "expression": "cat"},
				  {"id": 815, "expression": "DOG", "flags": ["CASELESS"]},
				  {"id": 1, "expression": "bird"}
				]}""");

		assertThat(scan("the dog chased the cat", MediaType.TEXT_PLAIN)).hasStatusOk()
			.bodyJson()
			.isStrictlyEqualTo("""
					{"version": 1, "matches": [815, 4711]}""");
		assertThat(scan("bird", MediaType.APPLICATION_OCTET_STREAM)).hasStatusOk()
			.bodyJson()
			.extractingPath("$.matches")
			.isEqualTo(List.of(1));
	}

	@Test
	void savingAgainReplacesTheProfile() {
		givenProfile("""
				{"patterns": [{"id": 1, "expression": "cat"}]}""");
		givenProfile("""
				{"patterns": [{"id": 2, "expression": "dog"}]}""");

		assertThat(scan("cat dog", MediaType.TEXT_PLAIN)).hasStatusOk()
			.bodyJson()
			.isStrictlyEqualTo("""
					{"version": 2, "matches": [2]}""");
	}

	@Test
	void isNotFoundUntilAProfileIsSaved() {
		assertThat(scan("content", MediaType.TEXT_PLAIN)).hasStatus(HttpStatus.NOT_FOUND)
			.bodyJson()
			.extractingPath("$.detail")
			.isEqualTo("No search profile has been saved yet");
		assertThat(this.mvc.get().uri("/api/profile")).hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void rejectsContentAboveTheLimit() {
		givenProfile("""
				{"patterns": [{"id": 1, "expression": "x"}]}""");

		assertThat(scan("x".repeat(33), MediaType.TEXT_PLAIN)).hasStatus(HttpStatus.CONTENT_TOO_LARGE);
		assertThat(scan("x".repeat(32), MediaType.TEXT_PLAIN)).hasStatusOk();
	}

	@Test
	void rejectsInvalidProfiles() {
		assertThat(save("""
				{"patterns": [{"id": 1, "expression": "ok"}, {"id": 2, "expression": "broken("}]}"""))
			.hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.extractingPath("$.detail")
			.asString()
			.startsWith("Pattern 2 is invalid");
		assertThat(save("""
				{"patterns": [{"expression": "no id"}]}""")).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(save("""
				{"patterns": [{"id": 1, "expression": "x", "flags": ["NOPE"]}]}""")).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(save("""
				{"patterns": []}""")).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(this.mvc.get().uri("/api/profile")).hasStatus(HttpStatus.NOT_FOUND);
	}

	private void givenProfile(String json) {
		assertThat(save(json)).hasStatusOk();
	}

	private MockMvcTester.MockMvcRequestBuilder save(String json) {
		return this.mvc.put().uri("/api/profile").contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private MockMvcTester.MockMvcRequestBuilder scan(String content, MediaType type) {
		return this.mvc.post().uri("/api/scan").contentType(type).content(content);
	}

}
