package com.cmo2.integrations.gohighlevel;

import com.cmo2.shared.exceptions.ApiException;
import org.springframework.http.HttpStatus;

/**
 * A GoHighLevel API call that failed after retries. Surfaces to clients as 502 Bad Gateway;
 * {@link #status()} is GHL's own HTTP status, or 0 if GHL was unreachable.
 */
public class GhlApiException extends ApiException {

    private final int ghlStatus;

    public GhlApiException(int ghlStatus, String message) {
        super(HttpStatus.BAD_GATEWAY, message);
        this.ghlStatus = ghlStatus;
    }

    public int status() {
        return ghlStatus;
    }

    public boolean isUnauthorized() {
        return ghlStatus == 401;
    }
}
