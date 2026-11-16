package com.example.flashsale;

import java.util.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public class Recovery {
    final JdbcTemplate db;
    final Reservations reservations;
    final TransactionTemplate tx;
    final MeterRegistry metrics;

    public Recovery(JdbcTemplate db, Reservations reservations, TransactionTemplate tx, MeterRegistry metrics) {
        this.db = db;
        this.reservations = reservations;
        this.tx = tx;
        this.metrics = metrics;
    }

    private static long units(Map<String, Object> row, String field) {
        return ((Number) row.get(field)).longValue();
    }

    @Scheduled(fixedDelay = 1000)
    public void schedules() {
        db.update("UPDATE flash_sales SET state='ACTIVE',version=version+1 WHERE state='SCHEDULED' AND starts_at<=clock_timestamp() AND ends_at>clock_timestamp()");
        db.update("UPDATE flash_sales SET state='COMPLETED',version=version+1 WHERE state IN ('ACTIVE','PAUSED','SCHEDULED') AND ends_at<=clock_timestamp()");
    }

    @Scheduled(fixedDelay = 1000)
    public void expire() {
        // Short separate transactions; duplicate scans are safe across pods.
        for (UUID id : db.queryForList("SELECT id FROM inventory_reservations WHERE status='RESERVED' AND expires_at<=clock_timestamp() ORDER BY expires_at LIMIT 100", UUID.class)) {
            try {
                reservations.transition(id, "EXPIRED", "worker", true);
            } catch (org.springframework.dao.TransientDataAccessException e) {
                metrics.counter("expiration.retries").increment();
            }
        }
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 30000)
    public void scheduleReconciliation() {
        reconcile();
    }

    public int reconcile() {
        int checkedPools = tx.execute(status -> {
            var pools = db.queryForList("SELECT * FROM inventory_allocations ORDER BY (SELECT max(created_at) FROM reconciliation_tasks t WHERE t.allocation_id=inventory_allocations.id) NULLS FIRST,id FOR UPDATE SKIP LOCKED LIMIT 100");
            for (var p : pools) {
                var totals = db.queryForMap("""
                        SELECT COALESCE(sum(r.quantity) FILTER(WHERE r.status='RESERVED'),0) AS held,
                               COALESCE(sum(r.quantity) FILTER(WHERE r.status='CONFIRMED'),0) AS committed,
                               COALESCE(sum(f.quantity),0) AS fulfilled,
                               count(*) FILTER(WHERE f.reservation_id IS NOT NULL
                                   AND (r.status<>'CONFIRMED' OR f.quantity<>r.quantity)) AS invalid_fulfillments
                        FROM inventory_reservations r LEFT JOIN inventory_fulfillments f ON f.reservation_id=r.id
                        WHERE r.allocation_id=?
                        """, p.get("id"));
                var ledger = db.queryForMap("""
                        SELECT COALESCE(sum(quantity) FILTER(WHERE kind='FULFILL'),0) AS shipped,
                               COALESCE(sum(quantity) FILTER(WHERE kind='POOL_CLOSE'),0) AS released,
                               count(*) FILTER(WHERE kind='POOL_CLOSE') AS closures
                        FROM inventory_movements WHERE allocation_id=?
                        """, p.get("id"));
                boolean ok = units(p, "reserved") == units(totals, "held")
                        && units(p, "committed") == units(totals, "committed")
                        && units(p, "fulfilled") == units(totals, "fulfilled")
                        && units(p, "fulfilled") == units(ledger, "shipped")
                        && units(p, "released") == units(ledger, "released")
                        && units(ledger, "closures") == (p.get("closed_at") == null ? 0 : 1)
                        && units(totals, "invalid_fulfillments") == 0;
                db.update("INSERT INTO reconciliation_tasks(id,allocation_id,state,details) VALUES(?,?,?,?)",
                        UUID.randomUUID(), p.get("id"), ok ? "CONSISTENT" : "MANUAL_REVIEW", totals + "; ledger=" + ledger);
                if (!ok) metrics.counter("inventory.invariant.violations").increment();
            }
            return pools.size();
        });
        // Separate transaction: lock warehouse rows, never lock pools while holding their parent.
        // Pool mutations cannot commit until their matching warehouse mutation commits, so MVCC
        // observes the corresponding old counters if a concurrent child mutation is waiting here.
        int checkedWarehouses = tx.execute(status -> {
            var stocks = db.queryForList("""
                    SELECT * FROM inventory i ORDER BY
                      (SELECT max(created_at) FROM reconciliation_tasks t
                       WHERE t.allocation_id IS NULL AND t.sku_id=i.sku_id AND t.warehouse_id=i.warehouse_id) NULLS FIRST,
                      sku_id,warehouse_id FOR UPDATE SKIP LOCKED LIMIT 100
                    """);
            for (var stock : stocks) {
                var totals = db.queryForMap("""
                        SELECT COALESCE(sum(capacity-fulfilled-released),0) AS encumbered,
                               COALESCE(sum(fulfilled),0) AS fulfilled
                        FROM inventory_allocations WHERE sku_id=? AND warehouse_id=?
                        """, stock.get("sku_id"), stock.get("warehouse_id"));
                boolean ok = units(stock, "allocated") == units(totals, "encumbered")
                        && units(stock, "fulfilled") == units(totals, "fulfilled");
                db.update("INSERT INTO reconciliation_tasks(id,sku_id,warehouse_id,state,details) VALUES(?,?,?,?,?)",
                        UUID.randomUUID(), stock.get("sku_id"), stock.get("warehouse_id"),
                        ok ? "CONSISTENT" : "MANUAL_REVIEW", totals.toString());
                if (!ok) metrics.counter("inventory.invariant.violations").increment();
            }
            return stocks.size();
        });
        return checkedPools + checkedWarehouses;
    }
}
