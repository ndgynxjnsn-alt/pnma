package com.example.springboot.profile;

public class NoProfileException extends RuntimeException {

	public NoProfileException() {
		super("No search profile has been saved yet");
	}

}
