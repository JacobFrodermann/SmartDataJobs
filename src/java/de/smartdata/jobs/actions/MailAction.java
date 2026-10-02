package de.smartdata.jobs.actions;

import de.smartdata.jobs.config.Configuration;
import de.fhbielefeld.scl.rest.util.JSONFormatter;
import de.ngi.logging.Logger;
import jakarta.activation.DataHandler;
import jakarta.activation.FileDataSource;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.File;
import java.util.Date;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Action for sending emails
 *
 * @author Florian Fehring
 */
public class MailAction extends Action {

    private String smtp_server = null;
    private String smtp_port = null;
    private String smtp_username = null;
    private String smtp_password = null;
    private String smtp_auth = null;

    private InternetAddress sender = null;
    private InternetAddress reciver = null;
    private InternetAddress[] ccs = null;
    private InternetAddress[] bccs = null;
    private String subject = null;
    private String content = null;
    private String[] files = null;

    @Override
    public JsonArray getParamsDescription() {
        JsonArrayBuilder jab = Json.createArrayBuilder();

        JsonObjectBuilder p1 = Json.createObjectBuilder();
        p1.add("name", "sender");
        p1.add("type", "email");
        p1.add("description", "Senders email address");
        p1.add("optional", false);
        jab.add(p1);
        JsonObjectBuilder p2 = Json.createObjectBuilder();
        p2.add("name", "reciver");
        p2.add("type", "email");
        p2.add("description", "Recivers email address. More emails may be added seperated by comata.");
        p2.add("optional", false);
        jab.add(p2);
        JsonObjectBuilder p3 = Json.createObjectBuilder();
        p3.add("name", "bbcs");
        p3.add("type", "email");
        p3.add("description", "Additional blind copy recivers.");
        p3.add("optional", false);
        jab.add(p3);
        JsonObjectBuilder p4 = Json.createObjectBuilder();
        p4.add("name", "subject");
        p4.add("type", "string");
        p4.add("description", "Mails subject");
        p4.add("optional", false);
        jab.add(p4);
        JsonObjectBuilder p5 = Json.createObjectBuilder();
        p5.add("name", "content");
        p5.add("type", "string");
        p5.add("description", "Mails text content");
        p5.add("optional", false);
        jab.add(p5);
        JsonObjectBuilder p6 = Json.createObjectBuilder();
        p6.add("name", "files");
        p6.add("type", "string");
        p6.add("description", "List of files to send with the mail. Each file must be separated by comata. Files are read from the servers file system.");
        p6.add("optional", true);
        jab.add(p6);

        return jab.build();
    }

    @Override
    public void setParams(JsonObject params) throws ActionConfigurationException {
        // Check configuration
        Configuration conf = new Configuration();
        this.smtp_server = conf.getProperty("smtp.server");
        this.smtp_port = conf.getProperty("smtp.port");
        this.smtp_auth = conf.getProperty("smtp.auth");
        if (this.smtp_auth != null && this.smtp_auth.equalsIgnoreCase("true")) {
            this.smtp_username = conf.getProperty("smtp.username");
            if(this.smtp_username == null || this.smtp_username.isEmpty()) {
                throw new ActionConfigurationException("Configuration error: Auth is activated, but >username< is missing.");
            }
            this.smtp_password = conf.getProperty("smtp.password");
            if(this.smtp_password == null || this.smtp_password.isEmpty()) {
                throw new ActionConfigurationException("Configuration error: Auth is activated, but >password< is missing.");
            }
        }

        // Mail pattern
        Pattern pattern = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$");

        // Check params configured in database
        String senderstr = params.getString("sender", null);
        if (senderstr == null) {
            throw new ActionConfigurationException("Parameter >sender< is missing.");
        }
        if (!pattern.matcher(senderstr).matches()) {
            throw new ActionConfigurationException("Sender email >" + params.getString("sender") + "< is not a valid email adress.");
        }
        try {
            this.sender = new InternetAddress(senderstr);
        } catch (AddressException ex) {
            throw new ActionConfigurationException("Sender address >" + reciver + "< is invalid: " + ex.getLocalizedMessage());
        }
        if (params.getString("reciver") == null) {
            throw new ActionConfigurationException("Parameter >reciver< is missing.");
        }
        String[] recivers = params.getString("reciver").split(",");
        this.ccs = new InternetAddress[recivers.length - 1];
        int i = 0;

        for (String reciver : recivers) {
            if (reciver == null || !pattern.matcher(reciver).matches()) {
                throw new ActionConfigurationException("Reciver address >" + reciver + "< is invalid.");
            }
            try {
                if (i == 0) {
                    this.reciver = new InternetAddress(reciver);
                } else {
                    this.ccs[i - 1] = (new InternetAddress(reciver));
                }
            } catch (AddressException ex) {
                throw new ActionConfigurationException("Reciver address >" + reciver + "< is invalid.");
            }
        }

        if (params.getString("bbcs", null) != null) {
            String[] bccs = params.getString("bbcs").split(",");
            this.bccs = new InternetAddress[bccs.length];
            for (String bcc : bccs) {
                if (!pattern.matcher(bcc).matches()) {
                    throw new ActionConfigurationException("Reciver address >" + bcc + "< is invalid.");
                }
                try {
                    this.bccs[i - 1] = (new InternetAddress(bcc));
                } catch (AddressException ex) {
                    throw new ActionConfigurationException("Reciver address >" + bcc + "< is invalid.");
                }
            }
        }

        String lfiles = params.getString("files", null);
        if (lfiles != null) {
            this.files = params.getString("files").split(",");
        }

        String lsubject = params.getString("subject", null);
        if (lsubject == null) {
            throw new ActionConfigurationException("Parameter >subject< is missing.");
        } else {
            this.subject = lsubject;
        }

        String lcontent = params.getString("content", null);
        if (lcontent == null) {
            throw new ActionConfigurationException("Parameter >content< is missing.");
        } else {
            this.content = lcontent;
        }
    }

    @Override
    public JsonObject call() {
        Logger.log();
        JsonObjectBuilder result = Json.createObjectBuilder();

        Properties properties = new Properties();
        properties.put("mail.smtp.host", this.smtp_server);
        properties.put("mail.smtp.port", this.smtp_port);

        // Mail send session
        Session session;
        // Set security configuration
        if (this.smtp_auth != null) {
            properties.put("mail.smtp.auth", true);
            properties.put("mail.smtp.starttls.enable", "true");
            properties.put("mail.smtp.ssl.protocols", "TLSv1.2");
            properties.put("mail.smtp.ssl.trust", "*");

            session = Session.getInstance(properties, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    Configuration conf = new Configuration();
                    return new PasswordAuthentication(conf.getProperty("smtp.user"), conf.getProperty("smtp.password"));
                }
            });
        } else {
            session = Session.getInstance(properties);
        }
        try {
            Message message = new MimeMessage(session);
            message.setFrom(this.sender);
            message.setRecipient(Message.RecipientType.TO, this.reciver);
            if (this.ccs != null && this.ccs.length != 0) {
                message.setRecipients(Message.RecipientType.CC, this.ccs);
            }
            if (this.bccs != null && this.bccs.length != 0) {
                message.setRecipients(Message.RecipientType.CC, this.bccs);
            }
            message.setSubject(subject);
            if (this.files == null || this.files.length == 0) {
                message.setText(content);
            } else {
                MimeBodyPart contentPart = new MimeBodyPart();
                contentPart.setText(content);
                Multipart multipart = new MimeMultipart();
                multipart.addBodyPart(contentPart);
                for (int i = 0; i < this.files.length; ++i) {
                    // Check if file exists
                    File f = new File(this.files[i]);
                    if (f.exists() && !f.isDirectory()) {
                        MimeBodyPart currentPart = new MimeBodyPart();
                        FileDataSource fds = new FileDataSource(f);
                        currentPart.setDataHandler(new DataHandler(fds));
                        currentPart.setFileName(f.getName());
                        multipart.addBodyPart(currentPart);
                    }
                }
                message.setContent(multipart);
            }
            message.setSentDate(new Date());
            if (this.smtp_username == null || this.smtp_password == null) {
                Logger.log("Send message >"+subject+"< without auth");
                Transport.send(message);
                result.add("stateMsg","Send mail without auth");
                result.add("state", "finished");
            } else {
                Logger.log("Send message >"+subject+"< with auth");
                Transport.send(message, this.smtp_username, this.smtp_password);
                result.add("stateMsg","Send mail with auth");
                result.add("state", "finished");
            }
        } catch (MessagingException ex) {
            result.add("state", "failed");
            result.add("statemesg", "Error sending mail: " + JSONFormatter.escapeForJson(ex.getLocalizedMessage()));
        }
        return result.build();
    }
}
