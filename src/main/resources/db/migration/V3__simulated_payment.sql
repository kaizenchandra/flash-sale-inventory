CREATE TABLE simulated_payments
(
    reservation_id uuid PRIMARY KEY REFERENCES inventory_reservations,
    state          text        NOT NULL CHECK (state IN ('AUTHORIZED', 'CAPTURED', 'VOIDED')),
    created_at     timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX pending_checkout_sagas ON checkout_sagas (updated_at) WHERE state IN ('PENDING','AUTHORIZED');
