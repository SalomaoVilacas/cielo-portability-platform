package com.example.portability.integration;

public class IntegrationFailure extends RuntimeException {

    private final boolean retryable;

    public IntegrationFailure(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
