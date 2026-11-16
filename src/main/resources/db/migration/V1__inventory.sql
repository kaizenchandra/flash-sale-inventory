CREATE TABLE products
(
    id   uuid PRIMARY KEY,
    name text NOT NULL
);
CREATE TABLE skus
(
    id         uuid PRIMARY KEY,
    product_id uuid NOT NULL REFERENCES products,
    name       text NOT NULL
);
CREATE TABLE warehouses
(
    id   uuid PRIMARY KEY,
    name text NOT NULL
);
CREATE TABLE inventory
(
    sku_id       uuid REFERENCES skus,
    warehouse_id uuid REFERENCES warehouses,
    physical     bigint NOT NULL CHECK (physical >= 0),
    sellable     bigint NOT NULL CHECK (sellable >= 0 AND sellable <= physical),
    allocated    bigint NOT NULL DEFAULT 0 CHECK (allocated >= 0 AND allocated <= sellable),
    version      bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (sku_id, warehouse_id)
);
CREATE TABLE flash_sales
(
    id             uuid PRIMARY KEY,
    name           text        NOT NULL,
    state          text        NOT NULL CHECK (state IN
                                               ('DRAFT', 'SCHEDULED', 'ACTIVE', 'PAUSED', 'COMPLETED', 'CANCELLED')),
    starts_at      timestamptz NOT NULL,
    ends_at        timestamptz NOT NULL,
    customer_limit int         NOT NULL CHECK (customer_limit > 0),
    max_quantity   int         NOT NULL CHECK (max_quantity > 0),
    ttl_seconds    int         NOT NULL CHECK (ttl_seconds BETWEEN 1 AND 3600),
    version        bigint      NOT NULL DEFAULT 0,
    CHECK (ends_at > starts_at)
);
CREATE TABLE inventory_allocations
(
    id           uuid PRIMARY KEY,
    sale_id      uuid   NOT NULL REFERENCES flash_sales,
    sku_id       uuid   NOT NULL,
    warehouse_id uuid   NOT NULL,
    capacity     bigint NOT NULL CHECK (capacity > 0),
    reserved     bigint NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    committed    bigint NOT NULL DEFAULT 0 CHECK (committed >= 0),
    version      bigint NOT NULL DEFAULT 0,
    FOREIGN KEY (sku_id, warehouse_id) REFERENCES inventory,
    UNIQUE (sale_id, sku_id, warehouse_id),
    CHECK (reserved + committed <= capacity)
);
CREATE TABLE customer_usage
(
    sale_id     uuid REFERENCES flash_sales,
    customer_id text,
    quantity    bigint NOT NULL DEFAULT 0 CHECK (quantity >= 0),
    PRIMARY KEY (sale_id, customer_id)
);
CREATE TABLE idempotency_records
(
    customer_id    text,
    sale_id        uuid,
    operation_key  text,
    fingerprint    text NOT NULL,
    reservation_id uuid NOT NULL UNIQUE,
    PRIMARY KEY (customer_id, sale_id, operation_key)
);
CREATE TABLE inventory_reservations
(
    id            uuid PRIMARY KEY,
    sale_id       uuid        NOT NULL REFERENCES flash_sales,
    allocation_id uuid REFERENCES inventory_allocations,
    customer_id   text        NOT NULL,
    quantity      int         NOT NULL CHECK (quantity > 0),
    status        text        NOT NULL CHECK (status IN ('RESERVED', 'CONFIRMED', 'CANCELLED', 'EXPIRED', 'FAILED')),
    reason        text,
    expires_at    timestamptz NOT NULL,
    version       bigint      NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX due_reservations ON inventory_reservations (expires_at, id) WHERE status='RESERVED';
CREATE INDEX customer_reservations ON inventory_reservations (customer_id, created_at DESC);
CREATE TABLE inventory_movements
(
    id             uuid PRIMARY KEY,
    allocation_id  uuid REFERENCES inventory_allocations,
    reservation_id uuid REFERENCES inventory_reservations,
    kind           text        NOT NULL,
    quantity       bigint      NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE outbox_events
(
    id                uuid PRIMARY KEY,
    aggregate_id      uuid        NOT NULL,
    aggregate_version bigint      NOT NULL,
    schema_version    int         NOT NULL DEFAULT 1,
    correlation_id    uuid        NOT NULL,
    type              text        NOT NULL,
    payload           text        NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT clock_timestamp(),
    published_at      timestamptz,
    attempts          int         NOT NULL DEFAULT 0
);
CREATE INDEX unpublished ON outbox_events (created_at) WHERE published_at IS NULL;
CREATE TABLE processed_events
(
    event_id    uuid PRIMARY KEY,
    received_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE event_projection
(
    aggregate_id uuid PRIMARY KEY,
    version      bigint NOT NULL,
    payload      text   NOT NULL
);
CREATE TABLE reconciliation_tasks
(
    id            uuid PRIMARY KEY,
    allocation_id uuid REFERENCES inventory_allocations,
    state         text        NOT NULL,
    details       text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE checkout_sagas
(
    reservation_id    uuid PRIMARY KEY REFERENCES inventory_reservations,
    state             text        NOT NULL,
    payment_reference text        NOT NULL,
    updated_at        timestamptz NOT NULL DEFAULT clock_timestamp()
);
