package com.example.flashsale;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public class Telemetry {
    final JdbcTemplate db;
    final AtomicLong held = new AtomicLong(), committed = new AtomicLong(), available = new AtomicLong(), expiryLag = new AtomicLong(), outboxAge = new AtomicLong(), outboxCount = new AtomicLong();

    final AtomicLong fulfilled = new AtomicLong(), outstanding = new AtomicLong(), released = new AtomicLong();

    public Telemetry(JdbcTemplate db, MeterRegistry registry) {
        this.db = db;
        registry.gauge("inventory.held.units", held);
        registry.gauge("inventory.committed.units", committed);
        registry.gauge("inventory.available.units", available);
        registry.gauge("inventory.fulfilled.units", fulfilled);
        registry.gauge("inventory.committed.outstanding.units", outstanding);
        registry.gauge("inventory.released.units", released);
        registry.gauge("reservation.expiration.lag.seconds", expiryLag);
        registry.gauge("outbox.oldest.seconds", outboxAge);
        registry.gauge("outbox.pending", outboxCount);
    }

    @Scheduled(fixedDelay = 10000, initialDelay = 10000)
    public void refresh() {
        var counters = db.queryForMap("SELECT COALESCE(sum(reserved),0) AS held,COALESCE(sum(committed),0) AS committed,COALESCE(sum(CASE WHEN closed_at IS NULL THEN capacity-reserved-committed ELSE 0 END),0) AS available,COALESCE(sum(fulfilled),0) AS fulfilled,COALESCE(sum(committed-fulfilled),0) AS outstanding,COALESCE(sum(released),0) AS released FROM inventory_allocations");
        held.set(((Number) counters.get("held")).longValue());
        committed.set(((Number) counters.get("committed")).longValue());
        available.set(((Number) counters.get("available")).longValue());
        fulfilled.set(((Number) counters.get("fulfilled")).longValue());
        outstanding.set(((Number) counters.get("outstanding")).longValue());
        released.set(((Number) counters.get("released")).longValue());
        expiryLag.set(db.queryForObject("SELECT COALESCE(GREATEST(EXTRACT(EPOCH FROM clock_timestamp()-min(expires_at)),0),0)::bigint FROM inventory_reservations WHERE status='RESERVED'", Long.class));
        var outbox = db.queryForMap("SELECT count(*) AS pending,COALESCE(EXTRACT(EPOCH FROM clock_timestamp()-min(created_at)),0)::bigint AS age FROM outbox_events WHERE published_at IS NULL");
        outboxAge.set(((Number) outbox.get("age")).longValue());
        outboxCount.set(((Number) outbox.get("pending")).longValue());
    }
}
