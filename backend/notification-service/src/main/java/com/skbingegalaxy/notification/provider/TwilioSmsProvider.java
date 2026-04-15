package com.skbingegalaxy.notification.provider;

import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TwilioSmsProvider implements SmsProvider {

    private final String accountSid;
    private final String authToken;
    private final String fromNumber;

    public TwilioSmsProvider(String accountSid, String authToken, String fromNumber) {
        this.accountSid = accountSid;
        this.authToken = authToken;
        this.fromNumber = fromNumber;
    }

    @PostConstruct
    void init() {
        Twilio.init(accountSid, authToken);
        log.info("Twilio SMS provider initialised (from={})", fromNumber);
    }

    @Override
    public String send(String toPhone, String body) {
        Message msg = Message.creator(
                new PhoneNumber(toPhone),
                new PhoneNumber(fromNumber),
                body
        ).create();
        log.info("SMS sent via Twilio to {} — SID {}", toPhone, msg.getSid());
        return msg.getSid();
    }

    @Override
    public String providerName() {
        return "twilio";
    }
}
