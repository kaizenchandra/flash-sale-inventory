package com.example.flashsale;

import java.util.*;
import java.sql.*;

import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.micrometer.core.instrument.MeterRegistry;

import static com.example.flashsale.Model.*;
import static com.example.flashsale.Problem.*;

@Service
public class Reservations {
    static final RowMapper<Reservation> MAPPER = (r, n) -> new Reservation(r.getObject("id", UUID.class), r.getObject("sale_id", UUID.class), r.getObject("allocation_id", UUID.class), r.getString("customer_id"), r.getInt("quantity"), Status.valueOf(r.getString("status")), r.getString("reason"), r.getTimestamp("expires_at").toInstant(), r.getLong("version"));
    final JdbcTemplate db;
    final MeterRegistry metrics;

    public Reservations(JdbcTemplate db, MeterRegistry metrics) {
        this.db = db;
        this.metrics = metrics;
    }

    public Reservation get(UUID id) {
        var rows = db.query("SELECT * FROM inventory_reservations WHERE id=?", MAPPER, id);
        if (rows.isEmpty()) throw new Rejected(404, "Reservation not found");
        return rows.getFirst();
    }

    public Reservation owned(UUID id, String user, boolean admin) {
        var r = get(id);
        if (!admin && !r.customerId().equals(user)) throw new Rejected(404, "Reservation not found");
        return r;
    }

    @Transactional
    public Reservation attempt(Try cmd, String customer, String key, boolean emptyCancel) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{8,128}"))
            throw new Rejected(400, "Idempotency-Key must be 8..128 URL-safe characters");
        metrics.counter("reservation.attempts").increment();
        var saleRows = db.queryForList("SELECT *, clock_timestamp() BETWEEN starts_at AND ends_at AS in_window FROM flash_sales WHERE id=? FOR SHARE", cmd.saleId());
        if (saleRows.isEmpty()) throw new Rejected(404, "Sale not found");
        var sale = saleRows.getFirst();
        UUID id = UUID.randomUUID();
        int inserted = db.update("INSERT INTO idempotency_records VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING", customer, cmd.saleId(), key, cmd.fingerprint(), id);
        var idem = db.queryForMap("SELECT * FROM idempotency_records WHERE customer_id=? AND sale_id=? AND operation_key=? FOR UPDATE", customer, cmd.saleId(), key);
        if (!idem.get("fingerprint").equals(cmd.fingerprint()))
            throw new Rejected(409, "Key reused with different payload");
        if (inserted == 0) {
            metrics.counter("reservation.idempotency.hits").increment();
            var existing = get((UUID) idem.get("reservation_id"));
            return emptyCancel ? transition(existing.id(), "CANCELLED", customer, false) : existing;
        }
        String reason = null;
        if (!emptyCancel && (!sale.get("state").equals("ACTIVE") || !Boolean.TRUE.equals(sale.get("in_window"))))
            reason = "SALE_INACTIVE";
        if (cmd.quantity() > ((Number) sale.get("max_quantity")).intValue()) reason = "QUANTITY_LIMIT";
        var pools = db.queryForList("SELECT * FROM inventory_allocations WHERE id=? AND sale_id=? FOR UPDATE", cmd.allocationId(), cmd.saleId());
        if (pools.isEmpty()) throw new Rejected(404, "Allocation not found");
        if (!emptyCancel && pools.getFirst().get("closed_at") != null) reason = "POOL_CLOSED";
        db.update("INSERT INTO customer_usage(sale_id,customer_id) VALUES(?,?) ON CONFLICT DO NOTHING", cmd.saleId(), customer);
        if (reason == null && !emptyCancel) {
            int usage = db.update("UPDATE customer_usage SET quantity=quantity+? WHERE sale_id=? AND customer_id=? AND quantity+?<=?", cmd.quantity(), cmd.saleId(), customer, cmd.quantity(), sale.get("customer_limit"));
            if (usage == 0) reason = "CUSTOMER_LIMIT";
            else if (db.update("UPDATE inventory_allocations SET reserved=reserved+?,version=version+1 WHERE id=? AND closed_at IS NULL AND reserved+committed+?<=capacity", cmd.quantity(), cmd.allocationId(), cmd.quantity()) == 0) {
                db.update("UPDATE customer_usage SET quantity=quantity-? WHERE sale_id=? AND customer_id=?", cmd.quantity(), cmd.saleId(), customer);
                reason = "SOLD_OUT";
            }
        }
        String state = emptyCancel ? "CANCELLED" : reason == null ? "RESERVED" : "FAILED";
        db.update("INSERT INTO inventory_reservations(id,sale_id,allocation_id,customer_id,quantity,status,reason,expires_at) VALUES(?,?,?,?,?,?,?,clock_timestamp()+(? * interval '1 second'))", id, cmd.saleId(), cmd.allocationId(), customer, cmd.quantity(), state, reason, sale.get("ttl_seconds"));
        Reservation r = get(id);
        if (state.equals("RESERVED")) movement(r, "RESERVE");
        event(r);
        metrics.counter("reservation.results", "status", state).increment();
        return r;
    }

    @Transactional
    public Reservation transition(UUID id, String target, String customer, boolean admin) {
        var rows = db.query("SELECT * FROM inventory_reservations WHERE id=? FOR UPDATE", MAPPER, id);
        if (rows.isEmpty()) throw new Rejected(404, "Reservation not found");
        Reservation r = rows.getFirst();
        if (!admin && !r.customerId().equals(customer)) throw new Rejected(404, "Reservation not found");
        if (r.status() != Status.RESERVED) return r;
        boolean due = Boolean.TRUE.equals(db.queryForObject("SELECT expires_at<=clock_timestamp() FROM inventory_reservations WHERE id=?", Boolean.class, id));
        if (target.equals("EXPIRED") && !due) return r;
        String next = due ? "EXPIRED" : target;
        if (!Set.of("CONFIRMED", "CANCELLED", "EXPIRED").contains(next)) throw new Rejected(400, "Invalid transition");
        int changed = db.update("UPDATE inventory_reservations SET status=?,version=version+1 WHERE id=? AND status='RESERVED' AND version=?", next, id, r.version());
        if (changed != 1) throw new IllegalStateException("Locked transition lost");
        db.update("UPDATE inventory_allocations SET reserved=reserved-?,committed=committed+?,version=version+1 WHERE id=?", r.quantity(), next.equals("CONFIRMED") ? r.quantity() : 0, r.allocationId());
        if (!next.equals("CONFIRMED"))
            db.update("UPDATE customer_usage SET quantity=quantity-? WHERE sale_id=? AND customer_id=?", r.quantity(), r.saleId(), r.customerId());
        r = get(id);
        movement(r, next);
        event(r);
        metrics.counter("reservation.transitions", "status", next).increment();
        return r;
    }

    void movement(Reservation r, String kind) {
        db.update("INSERT INTO inventory_movements(id,allocation_id,reservation_id,kind,quantity) VALUES(?,?,?,?,?)", UUID.randomUUID(), r.allocationId(), r.id(), kind, r.quantity());
    }

    void event(Reservation r) {
        UUID event = UUID.randomUUID();
        String payload = "{\"eventId\":\"" + event + "\",\"aggregateId\":\"" + r.id() + "\",\"aggregateVersion\":" + r.version() + ",\"schemaVersion\":1,\"correlationId\":\"" + r.id() + "\",\"type\":\"Inventory" + r.status() + "\",\"timestamp\":\"" + java.time.Instant.now() + "\"}";
        db.update("INSERT INTO outbox_events(id,aggregate_id,aggregate_version,correlation_id,type,payload) VALUES(?,?,?,?,?,?)", event, r.id(), r.version(), r.id(), "Inventory" + r.status(), payload);
    }
}
