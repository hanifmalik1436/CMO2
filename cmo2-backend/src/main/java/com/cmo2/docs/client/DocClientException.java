package com.cmo2.docs.client;

/** A workspace-tool call failed. The message is safe to show the owner (no secrets). */
public class DocClientException extends RuntimeException {

    public DocClientException(String message) {
        super(message);
    }
}
