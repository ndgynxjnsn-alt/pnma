package com.example.springboot.web;

import java.util.concurrent.CompletableFuture;

import com.example.springboot.profile.ProfileInfo;
import com.example.springboot.profile.ProfileService;
import com.example.springboot.profile.ScanResult;
import com.example.springboot.scan.ScanProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ProfileController {

	private final ProfileService profiles;

	private final long maxContentBytes;

	public ProfileController(ProfileService profiles, ScanProperties properties) {
		this.profiles = profiles;
		this.maxContentBytes = properties.maxContentBytes().toBytes();
	}

	@PutMapping("/profile")
	public ProfileInfo save(@RequestBody SaveProfileRequest request) {
		return this.profiles.save(request.toDefinitions());
	}

	@GetMapping("/profile")
	public ProfileInfo get() {
		return this.profiles.get();
	}

	/** Scans the raw request body; any content type is accepted. */
	@PostMapping("/scan")
	public CompletableFuture<ScanResult> scan(@RequestBody(required = false) byte[] content) {
		byte[] body = (content != null) ? content : new byte[0];
		if (body.length > this.maxContentBytes) {
			throw new ContentTooLargeException(this.maxContentBytes);
		}
		return this.profiles.scan(body);
	}

}
