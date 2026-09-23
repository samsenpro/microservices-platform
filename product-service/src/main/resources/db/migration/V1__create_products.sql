CREATE TABLE products (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(120)   NOT NULL,
    description VARCHAR(1000),
    price       NUMERIC(12, 2) NOT NULL,
    stock       INTEGER        NOT NULL,
    active      BOOLEAN        NOT NULL DEFAULT TRUE,
    version     BIGINT         NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ    NOT NULL,
    updated_at  TIMESTAMPTZ    NOT NULL,
    CONSTRAINT ck_products_price CHECK (price > 0),
    CONSTRAINT ck_products_stock CHECK (stock >= 0)
);

CREATE INDEX ix_products_active ON products (active);
