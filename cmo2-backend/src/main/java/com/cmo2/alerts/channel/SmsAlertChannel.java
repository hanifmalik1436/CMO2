package com.cmo2.alerts.channel;

import com.cmo2.alerts.domain.Alert;
import com.cmo2.alerts.domain.ChannelType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * SMS delivery (Twilio in production). Without Twilio credentials it records intent so
 * the multi-channel pipeline is demonstrable; the real Twilio call drops in here.
 */
@Component
public class SmsAlertChannel implements AlertChannel {

    private static final Logger log = LoggerFactory.getLogger(SmsAlertChannel.class);

    @Override
    public ChannelType type() {
        return ChannelType.SMS;
    }

    @Override
    public boolean send(Alert alert, String target) {
        if (target == null || target.isBlank()) return false;
        log.info("[SMS alert · demo, no Twilio configured] to {} :: {}", target, alert.getMessage());
        return true; // recorded intent
    }
}
