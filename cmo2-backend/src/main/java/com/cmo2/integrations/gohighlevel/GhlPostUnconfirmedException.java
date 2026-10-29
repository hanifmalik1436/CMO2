package com.cmo2.integrations.gohighlevel;

/**
 * A create call that GHL never answered (unreachable, timed out, or a 5xx): it may or may not
 * have been created, so it must not be retried without a person checking first.
 */
public class GhlPostUnconfirmedException extends GhlApiException {

    public GhlPostUnconfirmedException(GhlApiException cause) {
        super(cause.status(), cause.getMessage());
        initCause(cause);
    }

    /** Whether a failed create leaves its outcome unknown. */
    public static boolean isAmbiguous(GhlApiException e) {
        return e.status() == 0 || e.status() == 408 || e.status() >= 500;
    }
}
