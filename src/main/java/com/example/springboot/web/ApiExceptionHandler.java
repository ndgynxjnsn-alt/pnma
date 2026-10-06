package com.example.springboot.web;

import java.util.concurrent.CompletionException;

import com.example.springboot.profile.InvalidProfileException;
import com.example.springboot.profile.NoProfileException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiExceptionHandler {

	@ExceptionHandler
	ProblemDetail noProfile(NoProfileException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
	}

	@ExceptionHandler
	ProblemDetail invalidProfile(InvalidProfileException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
	}

	@ExceptionHandler
	ProblemDetail tooLarge(ContentTooLargeException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, ex.getMessage());
	}

	@ExceptionHandler
	ProblemDetail completion(CompletionException ex) throws Throwable {
		// Async results are normally unwrapped by Spring MVC; handle the wrapper just in case.
		if (ex.getCause() instanceof NoProfileException noProfile) {
			return noProfile(noProfile);
		}
		throw (ex.getCause() != null) ? ex.getCause() : ex;
	}

}
