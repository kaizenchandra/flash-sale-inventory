-- Applied V1-V3 are immutable. Capacity and committed remain historical totals.
ALTER TABLE inventory
    ADD COLUMN fulfilled bigint NOT NULL DEFAULT 0 CHECK (fulfilled >= 0);
ALTER TABLE inventory_allocations
    ADD COLUMN fulfilled bigint NOT NULL DEFAULT 0,
    ADD COLUMN released bigint NOT NULL DEFAULT 0,
    ADD COLUMN closed_at timestamptz,
    ADD COLUMN closed_by text,
    ADD CONSTRAINT allocation_fulfillment_bounds CHECK (fulfilled >= 0 AND fulfilled <= committed),
    ADD CONSTRAINT allocation_release_bounds CHECK (released >= 0 AND reserved + committed + released <= capacity),
    ADD CONSTRAINT allocation_closure_accounting CHECK (
        (closed_at IS NULL AND closed_by IS NULL AND released = 0)
        OR (closed_at IS NOT NULL AND closed_by IS NOT NULL AND reserved = 0 AND released = capacity - committed)
    );
CREATE TABLE inventory_fulfillments
(
    reservation_id     uuid PRIMARY KEY REFERENCES inventory_reservations,
    shipment_reference varchar(128) NOT NULL UNIQUE,
    quantity           int          NOT NULL CHECK (quantity > 0),
    actor              text         NOT NULL,
    fulfilled_at       timestamptz  NOT NULL DEFAULT clock_timestamp()
);
CREATE UNIQUE INDEX one_fulfillment_movement ON inventory_movements (reservation_id) WHERE kind = 'FULFILL';
CREATE UNIQUE INDEX one_pool_close_movement ON inventory_movements (allocation_id) WHERE kind = 'POOL_CLOSE';
ALTER TABLE reconciliation_tasks
    ADD COLUMN sku_id uuid,
    ADD COLUMN warehouse_id uuid,
    ADD CONSTRAINT reconciliation_stock_identity FOREIGN KEY (sku_id, warehouse_id) REFERENCES inventory;
CREATE INDEX reconciliation_pool_history ON reconciliation_tasks (allocation_id, created_at DESC);
CREATE INDEX reconciliation_stock_history ON reconciliation_tasks (sku_id, warehouse_id, created_at DESC) WHERE allocation_id IS NULL;
