package com.cmo2.alerts.channel;

import com.cmo2.alerts.domain.Alert;
import com.cmo2.alerts.domain.ChannelType;
import com.cmo2.shared.mail.MailService;
import org.springframework.stereotype.Component;

/** Email delivery via the shared {@link MailService} (Mailhog locally, SES/SendGrid in prod). */
@Component
public class EmailAlertChannel implements AlertChannel {

    private final MailService mailService;

    public EmailAlertChannel(MailService mailService) {
        this.mailService = mailService;
    }

    @Override
    public ChannelType type() {
        return ChannelType.EMAIL;
    }

    @Override
    public boolean send(Alert alert, String target) {
        if (target == null || target.isBlank()) return false;
        mailService.send(target, "[" + alert.getSeverity() + "] CMO² alert: " + alert.getMetric(),
                alert.getMessage());
        return true;
    }
}
