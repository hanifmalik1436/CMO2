package com.cmo2.shared.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Best-effort transactional email. In local dev this targets Mailhog
 * (http://localhost:8025). Failures are logged, never propagated — a flaky mail
 * server must not break signup. Phase 6 swaps the transport for SendGrid/SES.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender mailSender;

    public MailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void send(String to, String subject, String body) {
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom("no-reply@cmo2.local");
            msg.setTo(to);
            msg.setSubject(subject);
            msg.setText(body);
            mailSender.send(msg);
            log.info("Sent email '{}' to {}", subject, to);
        } catch (Exception e) {
            log.warn("Failed to send email '{}' to {}: {}", subject, to, e.getMessage());
        }
    }
}
