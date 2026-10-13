package com.stockwise.erp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockwise.erp.auth.ErpUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class BusinessEvents {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public BusinessEvents(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void publish(ErpUser actor, String type, String entityType, String entityId, Object data) {
        try {
            String eventId = UUID.randomUUID().toString();
            String json = mapper.writeValueAsString(Map.of(
                    "eventId", eventId, "type", type, "entityType", entityType,
                    "entityId", entityId, "organizationId", actor.organizationId(), "actorId", actor.id(),
                    "occurredAt", Instant.now().toString(), "data", data));
            jdbc.update("""
                    INSERT INTO event_outbox(event_id,organization_id,topic,event_key,payload)
                    VALUES (?::uuid,?,'erp.business-events',?,?::jsonb)
                    """, eventId, actor.organizationId(), entityType + ":" + entityId, json);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not record business event", ex);
        }
    }
}
