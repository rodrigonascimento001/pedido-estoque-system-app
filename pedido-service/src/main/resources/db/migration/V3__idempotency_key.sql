CREATE TABLE idempotency_key (
    scope VARCHAR(255) NOT NULL,
    idem_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    pedido_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (scope, idem_key),
    CONSTRAINT fk_idempotency_key_pedido FOREIGN KEY (pedido_id) REFERENCES pedidos (id)
);

CREATE INDEX idx_idempotency_key_expires_at ON idempotency_key (expires_at);
