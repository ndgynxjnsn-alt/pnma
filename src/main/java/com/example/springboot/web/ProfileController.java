package com.example.springboot.web;

import java.util.concurrent.CompletableFuture;

import com.example.springboot.profile.ProfileInfo;
import com.example.springboot.profile.ProfileNotFoundException;
import com.example.springboot.profile.ProfileService;
import com.example.springboot.profile.ScanResult;
import com.example.springboot.scan.ScanProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/profiles")
public class ProfileController {

	private final ProfileService profiles;

	private final long maxContentBytes;

	public ProfileController(ProfileService profiles, ScanProperties properties) {
		this.profiles = profiles;
		this.maxContentBytes = properties.maxContentBytes().toBytes();
	}

	@PutMapping("/{name}")
	public ProfileInfo save(@PathVariable String name, @RequestBody SaveProfileRequest request) {
		return this.profiles.save(name, request.toDefinitions());
	}

	@GetMapping("/{name}")
	public ProfileInfo get(@PathVariable String name) {
		return this.profiles.get(name);
	}

	@DeleteMapping("/{name}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String name) {
		if (!this.profiles.delete(name)) {
			throw new ProfileNotFoundException(name);
		}
	}

	/** Scans the raw request body; any content type is accepted. */
	@PostMapping("/{name}/scan")
	public CompletableFuture<ScanResult> scan(@PathVariable String name,
			@RequestBody(required = false) byte[] content) {
		byte[] body = (content != null) ? content : new byte[0];
		if (body.length > this.maxContentBytes) {
			throw new ContentTooLargeException(this.maxContentBytes);
		}
		return this.profiles.scan(name, body);
	}

}
