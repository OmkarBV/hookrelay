package io.hookrelay.api.ingestion;

public class PayloadTooLargeException extends RuntimeException {

    public PayloadTooLargeException(long limitBytes) {
        super("Request body exceeds the " + limitBytes + " byte limit");
    }
}
