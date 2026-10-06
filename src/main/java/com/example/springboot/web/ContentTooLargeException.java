package com.example.springboot.web;

class ContentTooLargeException extends RuntimeException {

	ContentTooLargeException(long maxBytes) {
		super("Content exceeds the limit of " + maxBytes + " bytes");
	}

}
