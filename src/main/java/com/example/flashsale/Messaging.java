package com.example.flashsale;

import java.util.*;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.util.backoff.FixedBackOff;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name = "app.messaging", havingValue = "true")
public class Messaging {
    final JdbcTemplate db;
    final TransactionTemplate tx;
    final KafkaTemplate<String, String> kafka;
    final ObjectMapper json;

    public Messaging(JdbcTemplate db, TransactionTemplate tx, KafkaTemplate<String, String> kafka, ObjectMapper json) {
        this.db = db;
        this.tx = tx;
        this.kafka = kafka;
        this.json = json;
    }

    @Bean
    public DefaultErrorHandler kafkaErrors(KafkaTemplate<String, String> template) {
        var recoverer = new DeadLetterPublishingRecoverer(template);
        recoverer.setFailIfSendResultIsError(true);
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000, 3));
    }

    @Scheduled(fixedDelay = 1000)
    public void publish() {
        tx.executeWithoutResult(status -> {
            for (var event : db.queryForList("SELECT * FROM outbox_events WHERE published_at IS NULL ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 20")) {
                try {
                    kafka.send("inventory.events", event.get("aggregate_id").toString(), event.get("payload").toString()).get(6, TimeUnit.SECONDS);
                    db.update("UPDATE outbox_events SET published_at=clock_timestamp() WHERE id=?", event.get("id"));
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    db.update("UPDATE outbox_events SET attempts=attempts+1 WHERE id=?", event.get("id"));
                    break;
                }
            }
        });
    }

    @KafkaListener(topics = "inventory.events")
    public void consume(String payload) throws Exception {
        var event = json.readTree(payload);
        if (event.path("schemaVersion").asInt() != 1) throw new IllegalArgumentException("Unknown schema");
        UUID id = UUID.fromString(event.required("eventId").asText());
        UUID aggregate = UUID.fromString(event.required("aggregateId").asText());
        long version = event.required("aggregateVersion").asLong();
        tx.executeWithoutResult(status -> {
            if (db.update("INSERT INTO processed_events(event_id) VALUES(?) ON CONFLICT DO NOTHING", id) == 0) return;
            db.update("INSERT INTO event_projection(aggregate_id,version,payload) VALUES(?,?,?) ON CONFLICT(aggregate_id) DO UPDATE SET version=EXCLUDED.version,payload=EXCLUDED.payload WHERE event_projection.version<EXCLUDED.version", aggregate, version, payload);
        });
    }
}
