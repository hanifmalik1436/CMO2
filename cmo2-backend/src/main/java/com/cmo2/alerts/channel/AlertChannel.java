package com.cmo2.alerts.channel;

import com.cmo2.alerts.domain.Alert;
import com.cmo2.alerts.domain.ChannelType;

/** A delivery channel for fired alerts (email, SMS, Slack, in-app). */
public interface AlertChannel {

    ChannelType type();

    /** Deliver the alert. Returns true if delivered (or recorded for a stubbed channel). */
    boolean send(Alert alert, String target);
}
