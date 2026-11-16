package com.example.flashsale;

import java.util.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.example.flashsale.Model.*;
import static com.example.flashsale.Problem.*;

@Service
public class Catalog {
    final JdbcTemplate db;

    public Catalog(JdbcTemplate db) {
        this.db = db;
    }

    @Transactional
    public UUID sale(Sale s) {
        if (!s.endsAt().isAfter(s.startsAt())) throw new Rejected(400, "Invalid schedule");
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO flash_sales(id,name,state,starts_at,ends_at,customer_limit,max_quantity,ttl_seconds) VALUES(?,?,'DRAFT',?,?,?,?,?)", id, s.name(), java.sql.Timestamp.from(s.startsAt()), java.sql.Timestamp.from(s.endsAt()), s.customerLimit(), s.maxQuantity(), s.ttlSeconds());
        return id;
    }

    @Transactional
    public UUID allocate(UUID sale, Allocation a) {
        var states = db.queryForList("SELECT state FROM flash_sales WHERE id=? FOR UPDATE", sale);
        if (states.isEmpty()) throw new Rejected(404, "Sale not found");
        if (!states.getFirst().get("state").equals("DRAFT")) throw new Rejected(409, "Allocate only in DRAFT");
        if (db.update("UPDATE inventory SET allocated=allocated+?,version=version+1 WHERE sku_id=? AND warehouse_id=? AND allocated+?<=sellable", a.quantity(), a.skuId(), a.warehouseId(), a.quantity()) != 1)
            throw new Rejected(409, "Insufficient warehouse capacity");
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO inventory_allocations(id,sale_id,sku_id,warehouse_id,capacity) VALUES(?,?,?,?,?)", id, sale, a.skuId(), a.warehouseId(), a.quantity());
        return id;
    }

    @Transactional
    public void state(UUID id, String target) {
        String allowed = switch (target) {
            case "ACTIVE" -> "'DRAFT','SCHEDULED','PAUSED'";
            case "PAUSED" -> "'ACTIVE'";
            case "COMPLETED" -> "'ACTIVE','PAUSED'";
            case "CANCELLED" -> "'DRAFT','SCHEDULED','ACTIVE','PAUSED'";
            case "SCHEDULED" -> "'DRAFT'";
            default -> throw new Rejected(400, "Invalid state");
        };
        if (db.update("UPDATE flash_sales SET state=?,version=version+1 WHERE id=? AND state IN (" + allowed + ")", target, id) != 1)
            throw new Rejected(409, "Illegal sale transition");
    }

    @Transactional
    public void adjustIdempotent(UUID sku, Adjustment a, String actor, String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{8,128}")) throw new Rejected(400, "Invalid Idempotency-Key");
        String fingerprint = a.delta() + ":" + a.reason();
        int inserted = db.update("INSERT INTO adjustment_requests VALUES(?,?,?,?,?,clock_timestamp()) ON CONFLICT DO NOTHING", sku, a.warehouseId(), actor, key, fingerprint);
        String existing = db.queryForObject("SELECT fingerprint FROM adjustment_requests WHERE sku_id=? AND warehouse_id=? AND actor=? AND operation_key=?", String.class, sku, a.warehouseId(), actor, key);
        if (!fingerprint.equals(existing)) throw new Rejected(409, "Adjustment key payload mismatch");
        if (inserted == 1) adjustStock(sku, a, actor);
    }

    @Transactional
    public void adjust(UUID sku, Adjustment a) {
        adjustStock(sku, a, "inventory-engine");
    }

    private void adjustStock(UUID sku, Adjustment a, String actor) {
        if (db.update("UPDATE inventory SET physical=physical+?,sellable=sellable+?,version=version+1 WHERE sku_id=? AND warehouse_id=? AND sellable+?>=allocated AND physical+?>=0", a.delta(), a.delta(), sku, a.warehouseId(), a.delta(), a.delta()) != 1)
            throw new Rejected(409, "Adjustment would violate allocated stock");
        db.update("INSERT INTO inventory_movements(id,sku_id,warehouse_id,actor,kind,quantity) VALUES(?,?,?,?,?,?)", UUID.randomUUID(), sku, a.warehouseId(), actor, "ADJUST:" + a.reason(), a.delta());
    }
}
