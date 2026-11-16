package com.example.flashsale;

import java.util.*;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;

@Service
public class Checkout {
    final JdbcTemplate db;
    final Reservations reservations;
    final TransactionTemplate tx;

    public Checkout(JdbcTemplate db, Reservations reservations, TransactionTemplate tx) {
        this.db = db;
        this.reservations = reservations;
        this.tx = tx;
    }

    // Simulated PSP has durable, idempotent effects in separate transactions.
    // It is intentionally not atomic with inventory or the saga state record.
    public Map<String, Object> complete(UUID id, String customer) {
        reservations.owned(id, customer, false);
        tx.executeWithoutResult(s -> db.update("INSERT INTO checkout_sagas(reservation_id,state,payment_reference) VALUES(?,'PENDING',?) ON CONFLICT DO NOTHING", id, "simulated-" + id));
        return resume(id);
    }

    Map<String, Object> resume(UUID id) {
        tx.executeWithoutResult(s -> db.update("INSERT INTO simulated_payments(reservation_id,state) VALUES(?,'AUTHORIZED') ON CONFLICT DO NOTHING", id));
        tx.executeWithoutResult(s -> db.update("UPDATE checkout_sagas SET state='AUTHORIZED',updated_at=clock_timestamp() WHERE reservation_id=? AND state='PENDING'", id));
        var owner = reservations.get(id);
        var r = reservations.transition(id, "CONFIRMED", owner.customerId(), false);
        boolean confirmed = r.status() == Model.Status.CONFIRMED;
        // Crash here is recovered using the immutable inventory decision and stable PSP key.
        tx.executeWithoutResult(s -> db.update("UPDATE simulated_payments SET state=? WHERE reservation_id=?", confirmed ? "CAPTURED" : "VOIDED", id));
        String state = confirmed ? "ORDER_CONFIRMED" : "AUTHORIZATION_VOIDED";
        tx.executeWithoutResult(s -> db.update("UPDATE checkout_sagas SET state=?,updated_at=clock_timestamp() WHERE reservation_id=?", state, id));
        return Map.of("reservation", r, "checkout", state, "payment", "SIMULATED");
    }

    @Scheduled(fixedDelay = 2000)
    public void recover() {
        for (UUID id : db.queryForList("SELECT reservation_id FROM checkout_sagas WHERE state IN ('PENDING','AUTHORIZED') ORDER BY updated_at LIMIT 20", UUID.class))
            resume(id);
    }
}
