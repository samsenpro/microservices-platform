CREATE TABLE users (
    id         UUID         PRIMARY KEY,
    username   VARCHAR(50)  NOT NULL,
    email      VARCHAR(254) NOT NULL,
    -- Hash BCrypt, nunca la contraseña en claro
    password   VARCHAR(100) NOT NULL,
    role       VARCHAR(20)  NOT NULL,
    enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    version    BIGINT       NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN'))
);

-- Unicidad sin distinguir mayúsculas: "Alice" y "alice" son el mismo usuario
CREATE UNIQUE INDEX uk_users_username ON users (LOWER(username));
CREATE UNIQUE INDEX uk_users_email ON users (LOWER(email));
