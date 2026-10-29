package com.cmo2.shared.notify;

/**
 * Sends a short message to the agency's own team channel (Slack, when the tenant has connected
 * it). A port in the shared kernel so any module can notify without depending on the docs module.
 */
public interface TeamNotifier {

    /** Returns true if the message was delivered; false if no team channel is configured or delivery failed. */
    boolean notify(Long tenantId, String text);
}
