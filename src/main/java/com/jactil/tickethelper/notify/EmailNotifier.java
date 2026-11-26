package com.jactil.tickethelper.notify;

import com.jactil.tickethelper.core.config.Settings;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** SMTP 邮件。支持 465 直连 SSL 与 587 STARTTLS；不填账号则匿名发。 */
public final class EmailNotifier implements Notifier {

    private final Settings.Smtp cfg;

    public EmailNotifier(Settings.Smtp cfg) {
        this.cfg = cfg;
    }

    @Override
    public String name() {
        return "邮件";
    }

    @Override
    public boolean enabled() {
        return cfg != null && !cfg.host.isBlank() && !cfg.to.isBlank();
    }

    @Override
    public void send(String title, String body) throws Exception {
        Properties props = new Properties();
        props.put("mail.smtp.host", cfg.host);
        props.put("mail.smtp.port", String.valueOf(cfg.port));
        boolean auth = cfg.username != null && !cfg.username.isBlank();
        props.put("mail.smtp.auth", String.valueOf(auth));
        if (cfg.ssl) {
            props.put("mail.smtp.ssl.enable", "true");
        } else if (auth) {
            props.put("mail.smtp.starttls.enable", "true");
        }

        Session session = Session.getInstance(props, auth ? new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(cfg.username, cfg.password);
            }
        } : null);

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(cfg.from == null || cfg.from.isBlank() ? cfg.username : cfg.from));
        for (String to : cfg.to.split("[,;]")) {
            if (!to.isBlank()) {
                message.addRecipient(Message.RecipientType.TO, new InternetAddress(to.trim()));
            }
        }
        message.setSubject(title, StandardCharsets.UTF_8.name());
        message.setText(body, StandardCharsets.UTF_8.name());
        Transport.send(message);
    }
}
