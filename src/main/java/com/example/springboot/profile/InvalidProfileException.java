package com.example.springboot.profile;

public class InvalidProfileException extends RuntimeException {

	public InvalidProfileException(String message) {
		super(message);
	}

}
