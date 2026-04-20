package com.skbingegalaxy.notification.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
public class TwilioWhatsAppProvider implements WhatsAppProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String accountSid;
    private final String authToken;
    private final String fromNumber;

    public TwilioWhatsAppProvider(String accountSid, String authToken, String fromNumber) {
        this.accountSid = accountSid;
        this.authToken = authToken;
        this.fromNumber = fromNumber;
    }

    @PostConstruct
    void init() {
        Twilio.init(accountSid, authToken);
        log.info("Twilio WhatsApp provider initialised (from=whatsapp:{})", fromNumber);
    }

    @Override
    public String send(String toPhone, String body) {
        Message msg = Message.creator(
                new PhoneNumber("whatsapp:" + toPhone),
                new PhoneNumber("whatsapp:" + fromNumber),
                body
        ).create();
        log.info("WhatsApp sent via Twilio to {} — SID {}", toPhone, msg.getSid());
        return msg.getSid();
    }

    @Override
    public String sendWithContentSid(String toPhone, String contentSid, Map<String, String> variables) {
        var creator = Message.creator(
                new PhoneNumber("whatsapp:" + toPhone),
                new PhoneNumber("whatsapp:" + fromNumber),
                ""  // body is empty when using Content SID
        );
        creator.setContentSid(contentSid);
        if (variables != null && !variables.isEmpty()) {
            try {
                creator.setContentVariables(MAPPER.writeValueAsString(variables));
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize WhatsApp content variables: {}", e.getMessage());
                throw new RuntimeException("Failed to serialize content variables", e);
            }
        }
        Message msg = creator.create();
        log.info("WhatsApp (Content SID) sent via Twilio to {} — SID {} contentSid={}", toPhone, msg.getSid(), contentSid);
        return msg.getSid();
    }

    @Override
    public String providerName() {
        return "twilio";
    }
}
