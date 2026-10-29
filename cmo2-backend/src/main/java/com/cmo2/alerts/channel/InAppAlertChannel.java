package com.cmo2.alerts.channel;

import com.cmo2.alerts.domain.Alert;
import com.cmo2.alerts.domain.ChannelType;
import org.springframework.stereotype.Component;

/** In-app delivery is implicit — the persisted Alert IS the in-app notification. */
@Component
public class InAppAlertChannel implements AlertChannel {

    @Override
    public ChannelType type() {
        return ChannelType.IN_APP;
    }

    @Override
    public boolean send(Alert alert, String target) {
        return true;
    }
}
