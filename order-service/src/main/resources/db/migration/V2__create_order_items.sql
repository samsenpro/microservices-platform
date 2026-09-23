CREATE TABLE order_items (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id   UUID           NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    -- Referencia lógica al producto de product-service: sin FK, es otra base de datos.
    -- El precio se copia en el momento de la compra: cambios posteriores no alteran el pedido.
    product_id BIGINT         NOT NULL,
    quantity   INTEGER        NOT NULL,
    unit_price NUMERIC(12, 2) NOT NULL,
    subtotal   NUMERIC(14, 2) NOT NULL,
    CONSTRAINT ck_order_items_quantity CHECK (quantity > 0),
    CONSTRAINT ck_order_items_subtotal CHECK (subtotal = unit_price * quantity)
);

CREATE INDEX ix_order_items_order ON order_items (order_id);
