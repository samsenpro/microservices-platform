CREATE TABLE orders (
    id         UUID           PRIMARY KEY,
    -- Referencia lógica al usuario de user-service (sub del JWT): sin FK, es otra base de datos
    user_id    UUID           NOT NULL,
    status     VARCHAR(20)    NOT NULL,
    total      NUMERIC(14, 2) NOT NULL,
    version    BIGINT         NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ    NOT NULL,
    updated_at TIMESTAMPTZ    NOT NULL,
    CONSTRAINT ck_orders_status CHECK (status IN ('CREATED', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_orders_total CHECK (total >= 0)
);

CREATE INDEX ix_orders_user_created ON orders (user_id, created_at DESC);
