package com.example.demo;

import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/** Reacts to new accounts: publishes an integration event and asks the mail service to send a welcome email. */
@Component
public class WelcomeMailer {

    static final String ACCOUNT_TOPIC = "account-events";

    private final KafkaTemplate<String, String> kafka;
    private final RestTemplate rest;

    public WelcomeMailer(KafkaTemplate<String, String> kafka, RestTemplate rest) {
        this.kafka = kafka;
        this.rest = rest;
    }

    @EventListener
    public void onAccountCreated(AccountCreatedEvent event) {
        kafka.send(ACCOUNT_TOPIC, String.valueOf(event.accountId()), event.email());
        rest.postForObject("http://mail-service/api/welcome", event.email(), Void.class);
    }
}
