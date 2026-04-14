package com.example.test.exception;

public class AccountNumberGenerationException extends RuntimeException {
    public AccountNumberGenerationException(String message) {
        super(message);
    }
}
