package io.hookrelay.common.security;

public class SsrfViolationException extends RuntimeException {

    public SsrfViolationException(String message) {
        super(message);
    }
}
