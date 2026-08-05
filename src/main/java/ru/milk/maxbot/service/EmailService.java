package ru.milk.maxbot.service;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimeUtility;
import ru.milk.maxbot.config.AppConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;

public class EmailService {
    private final AppConfig config;

    public EmailService(AppConfig config) {
        this.config = config;
    }

    public boolean isConfigured() {
        return !isBlank(config.smtpHost()) && !isBlank(config.smtpFrom());
    }

    public void sendExcelReport(String recipientEmail,
                                Path file,
                                String fileName,
                                String subject,
                                String bodyText) {
        if (!isConfigured()) {
            throw new IllegalStateException("SMTP is not configured");
        }
        try {
            MimeMessage message = new MimeMessage(mailSession());
            message.setFrom(new InternetAddress(config.smtpFrom()));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipientEmail, false));
            message.setSubject(subject, "UTF-8");

            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setText(bodyText, "UTF-8");

            MimeBodyPart attachmentPart = new MimeBodyPart();
            attachmentPart.attachFile(file.toFile());
            attachmentPart.setFileName(MimeUtility.encodeText(fileName, "UTF-8", null));

            Multipart multipart = new MimeMultipart();
            multipart.addBodyPart(textPart);
            multipart.addBodyPart(attachmentPart);
            message.setContent(multipart);

            Transport.send(message);
        } catch (MessagingException | IOException e) {
            throw new IllegalStateException("Failed to send Excel report by email", e);
        }
    }

    private Session mailSession() {
        Properties properties = new Properties();
        properties.put("mail.smtp.host", config.smtpHost());
        properties.put("mail.smtp.port", String.valueOf(config.smtpPort()));
        properties.put("mail.smtp.starttls.enable", String.valueOf(config.smtpStartTls()));
        properties.put("mail.smtp.ssl.enable", String.valueOf(config.smtpSsl()));

        boolean authEnabled = !isBlank(config.smtpUsername()) && !isBlank(config.smtpPassword());
        properties.put("mail.smtp.auth", String.valueOf(authEnabled));
        if (!authEnabled) {
            return Session.getInstance(properties);
        }

        return Session.getInstance(properties, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(config.smtpUsername(), config.smtpPassword());
            }
        });
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
