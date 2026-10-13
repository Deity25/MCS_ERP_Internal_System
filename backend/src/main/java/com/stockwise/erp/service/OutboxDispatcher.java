package com.stockwise.erp.service;

import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class OutboxDispatcher {
    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;

    public OutboxDispatcher(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka) {
        this.jdbc = jdbc;
        this.kafka = kafka;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-ms:2000}")
    @Transactional
    public void dispatch() {
        List<OutboxRow> pending = jdbc.query("""
                SELECT id,topic,event_key,payload::text AS payload
                FROM event_outbox WHERE published_at IS NULL
                ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 5
                """, (rs, row) -> new OutboxRow(rs.getLong("id"), rs.getString("topic"), rs.getString("event_key"), rs.getString("payload")));
        for (OutboxRow event : pending) {
            try {
                var result = kafka.send(event.topic, event.key, event.payload).get(2, TimeUnit.SECONDS);
                RecordMetadata metadata = result.getRecordMetadata();
                jdbc.update("UPDATE event_outbox SET published_at=now(),attempt_count=attempt_count+1,last_error=NULL WHERE id=?",
                        event.id);
                log.debug("Published business event {} to partition {} offset {}", event.id, metadata.partition(), metadata.offset());
            } catch (Exception ex) {
                jdbc.update("UPDATE event_outbox SET attempt_count=attempt_count+1,last_error=? WHERE id=?",
                        limit(ex.getMessage()), event.id);
                log.warn("Business event {} remains queued for retry", event.id);
            }
        }
    }

    private String limit(String value) {
        if (value == null) return "Kafka publish failed";
        return value.length() > 800 ? value.substring(0, 800) : value;
    }

    private record OutboxRow(long id, String topic, String key, String payload) { }
}
