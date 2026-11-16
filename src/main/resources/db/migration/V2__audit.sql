ALTER TABLE inventory_movements
    ADD COLUMN sku_id uuid REFERENCES skus;
ALTER TABLE inventory_movements
    ADD COLUMN warehouse_id uuid REFERENCES warehouses;
ALTER TABLE inventory_movements
    ADD COLUMN actor text NOT NULL DEFAULT 'inventory-engine';
CREATE TABLE adjustment_requests
(
    sku_id        uuid REFERENCES skus,
    warehouse_id  uuid REFERENCES warehouses,
    actor         text        NOT NULL,
    operation_key text        NOT NULL,
    fingerprint   text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (sku_id, warehouse_id, actor, operation_key)
);
