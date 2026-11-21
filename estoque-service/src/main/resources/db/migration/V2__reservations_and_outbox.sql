CREATE TABLE reserva_pedido (
    pedido_id UUID NOT NULL PRIMARY KEY,
    payload_hash VARCHAR(64) NOT NULL,
    resultado VARCHAR(32) NOT NULL,
    motivo TEXT,
    criado_em TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_reserva_pedido_resultado CHECK (resultado IN ('PROCESSANDO', 'RESERVADO', 'INDISPONIVEL'))
);

CREATE TABLE reserva_pedido_item (
    pedido_id UUID NOT NULL,
    produto_id UUID NOT NULL,
    quantidade INTEGER NOT NULL,
    reservado BOOLEAN NOT NULL,
    CONSTRAINT pk_reserva_pedido_item PRIMARY KEY (pedido_id, produto_id),
    CONSTRAINT fk_reserva_pedido_item_pedido FOREIGN KEY (pedido_id) REFERENCES reserva_pedido (pedido_id),
    CONSTRAINT chk_reserva_pedido_item_quantidade CHECK (quantidade > 0)
);

CREATE TABLE outbox_event (
    event_id UUID NOT NULL PRIMARY KEY,
    event_type VARCHAR(150) NOT NULL,
    topic VARCHAR(200) NOT NULL,
    message_key VARCHAR(200) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE,
    claim_id UUID,
    claim_until TIMESTAMP WITH TIME ZONE,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT
);

CREATE INDEX idx_outbox_event_pending ON outbox_event (published_at, claim_until, created_at);
