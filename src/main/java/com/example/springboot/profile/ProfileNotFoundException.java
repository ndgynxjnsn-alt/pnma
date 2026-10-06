package com.example.springboot.profile;

public class ProfileNotFoundException extends RuntimeException {

	public ProfileNotFoundException(String name) {
		super("Search profile '" + name + "' does not exist");
	}

}
