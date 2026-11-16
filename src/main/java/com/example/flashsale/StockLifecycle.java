package com.example.flashsale;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.example.flashsale.Model.*;
import static com.example.flashsale.Problem.Rejected;

/**
 * Full-reservation dispatch and explicit pool closure. Neither operation calls a carrier.
 */
@Service
public class StockLifecycle {
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public StockLifecycle(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public Map<String, Object> pool(UUID id) {
        var rows = db.queryForList("""
                SELECT *, CASE WHEN closed_at IS NULL THEN capacity-reserved-committed ELSE 0 END AS available,
                       capacity-fulfilled-released AS encumbered, committed-fulfilled AS committed_outstanding
                FROM inventory_allocations WHERE id=?
                """, id);
        if (rows.isEmpty()) throw new Rejected(404, "Allocation not found");
        return rows.getFirst();
    }

    public Fulfillment fulfillment(UUID reservationId) {
        var rows = db.query("SELECT * FROM inventory_fulfillments WHERE reservation_id=?",
                (r, n) -> new Fulfillment(r.getObject("reservation_id", UUID.class), r.getString("shipment_reference"),
                        r.getInt("quantity"), r.getString("actor"), r.getTimestamp("fulfilled_at").toInstant()), reservationId);
        if (rows.isEmpty()) throw new Rejected(404, "Fulfillment not found");
        return rows.getFirst();
    }

    @Transactional
    public Fulfillment fulfill(UUID reservationId, Shipment command, String actor) {
        if (command == null || command.shipmentReference() == null
                || !command.shipmentReference().matches("[A-Za-z0-9_-]{8,128}"))
            throw new Rejected(400, "Shipment reference must be 8..128 URL-safe characters");
        // Same order as terminal reservation operations: reservation -> allocation -> warehouse.
        var rows = db.query("SELECT * FROM inventory_reservations WHERE id=? FOR UPDATE",
                Reservations.MAPPER, reservationId);
        if (rows.isEmpty()) throw new Rejected(404, "Reservation not found");
        var reservation = rows.getFirst();
        var existing = db.queryForList("SELECT shipment_reference FROM inventory_fulfillments WHERE reservation_id=?", reservationId);
        if (!existing.isEmpty()) {
            if (!existing.getFirst().get("shipment_reference").equals(command.shipmentReference()))
                throw new Rejected(409, "Reservation already dispatched with a different shipment reference");
            return fulfillment(reservationId);
        }
        if (reservation.status() != Status.CONFIRMED)
            throw new Rejected(409, "Only confirmed allocations can be dispatched");
        var stockPool = db.queryForMap("SELECT * FROM inventory_allocations WHERE id=? FOR UPDATE", reservation.allocationId());
        int quantity = reservation.quantity();
        // Reference uniqueness fences dispatch of a different reservation under the same shipment identity.
        db.update("INSERT INTO inventory_fulfillments(reservation_id,shipment_reference,quantity,actor) VALUES(?,?,?,?)",
                reservationId, command.shipmentReference(), quantity, actor);
        requireOne(db.update("UPDATE inventory_allocations SET fulfilled=fulfilled+?,version=version+1 WHERE id=? AND fulfilled+?<=committed",
                quantity, reservation.allocationId(), quantity));
        requireOne(db.update("""
                        UPDATE inventory SET physical=physical-?,sellable=sellable-?,allocated=allocated-?,
                            fulfilled=fulfilled+?,version=version+1
                        WHERE sku_id=? AND warehouse_id=? AND physical>=? AND sellable>=? AND allocated>=?
                        """, quantity, quantity, quantity, quantity, stockPool.get("sku_id"), stockPool.get("warehouse_id"),
                quantity, quantity, quantity));
        requireOne(db.update("UPDATE inventory_reservations SET version=version+1 WHERE id=? AND version=? AND status='CONFIRMED'",
                reservationId, reservation.version()));
        movement(stockPool, reservationId, "FULFILL", quantity, actor);
        event(reservationId, reservation.version() + 1, "InventoryFulfilled",
                Map.of("allocationId", reservation.allocationId(), "quantity", quantity,
                        "shipmentReference", command.shipmentReference()));
        return fulfillment(reservationId);
    }

    @Transactional
    public Map<String, Object> close(UUID allocationId, String actor) {
        // Read immutable identity before acquiring locks. Sale SHARE is compatible with existing Try locks.
        var identities = db.queryForList("SELECT sale_id FROM inventory_allocations WHERE id=?", allocationId);
        if (identities.isEmpty()) throw new Rejected(404, "Allocation not found");
        var sale = db.queryForMap("SELECT state FROM flash_sales WHERE id=? FOR SHARE", identities.getFirst().get("sale_id"));
        var stockPool = db.queryForMap("SELECT * FROM inventory_allocations WHERE id=? FOR UPDATE", allocationId);
        if (stockPool.get("closed_at") != null) return pool(allocationId);
        if (!sale.get("state").equals("COMPLETED") && !sale.get("state").equals("CANCELLED"))
            throw new Rejected(409, "Close requires a COMPLETED or CANCELLED sale");
        if (((Number) stockPool.get("reserved")).longValue() != 0)
            throw new Rejected(409, "Active holds must be cancelled or expired before closing the pool");
        var ownership = db.queryForMap("""
                SELECT COALESCE(sum(quantity) FILTER (WHERE status='RESERVED'),0) AS held,
                       COALESCE(sum(quantity) FILTER (WHERE status='CONFIRMED'),0) AS committed
                FROM inventory_reservations WHERE allocation_id=?
                """, allocationId);
        if (((Number) ownership.get("held")).longValue() != ((Number) stockPool.get("reserved")).longValue()
                || ((Number) ownership.get("committed")).longValue() != ((Number) stockPool.get("committed")).longValue())
            throw new Rejected(409, "Reservation totals disagree with the pool; manual review required");
        long released = ((Number) stockPool.get("capacity")).longValue() - ((Number) stockPool.get("committed")).longValue();
        // A closed pool keeps every confirmed but unshipped unit encumbered in the warehouse.
        requireOne(db.update("""
                UPDATE inventory_allocations SET released=?,closed_at=clock_timestamp(),closed_by=?,version=version+1
                WHERE id=? AND closed_at IS NULL AND reserved=0
                """, released, actor, allocationId));
        requireOne(db.update("UPDATE inventory SET allocated=allocated-?,version=version+1 WHERE sku_id=? AND warehouse_id=? AND allocated>=?",
                released, stockPool.get("sku_id"), stockPool.get("warehouse_id"), released));
        movement(stockPool, null, "POOL_CLOSE", released, actor);
        event(allocationId, ((Number) stockPool.get("version")).longValue() + 1,
                "InventoryPoolClosed", Map.of("released", released, "saleId", stockPool.get("sale_id")));
        return pool(allocationId);
    }

    private void requireOne(int changed) {
        if (changed != 1)
            throw new Rejected(409, "Authoritative stock accounting conflict; investigate before retrying");
    }

    private void movement(Map<String, Object> pool, UUID reservation, String kind, long quantity, String actor) {
        db.update("""
                INSERT INTO inventory_movements(id,allocation_id,reservation_id,sku_id,warehouse_id,actor,kind,quantity)
                VALUES(?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), pool.get("id"), reservation, pool.get("sku_id"), pool.get("warehouse_id"), actor, kind, quantity);
    }

    private void event(UUID aggregate, long version, String type, Map<String, Object> details) {
        UUID id = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>(details);
        payload.putAll(Map.of("eventId", id, "aggregateId", aggregate, "aggregateVersion", version,
                "schemaVersion", 1, "correlationId", aggregate, "type", type, "timestamp", Instant.now().toString()));
        try {
            db.update("INSERT INTO outbox_events(id,aggregate_id,aggregate_version,correlation_id,type,payload) VALUES(?,?,?,?,?,?)",
                    id, aggregate, version, aggregate, type, json.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot encode inventory event", e);
        }
    }
}
