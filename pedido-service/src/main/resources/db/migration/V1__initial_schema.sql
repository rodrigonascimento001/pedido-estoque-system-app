CREATE TABLE pedidos (
    id UUID NOT NULL PRIMARY KEY,
    status VARCHAR(255) NOT NULL,
    criado_em TIMESTAMP WITH TIME ZONE NOT NULL,
    atualizado_em TIMESTAMP WITH TIME ZONE,
    motivo_cancelamento VARCHAR(255)
);

CREATE TABLE pedido_itens (
    id UUID NOT NULL PRIMARY KEY,
    pedido_id UUID NOT NULL,
    produto_id UUID NOT NULL,
    quantidade INTEGER NOT NULL,
    preco_unitario NUMERIC(38, 2) NOT NULL,
    CONSTRAINT fk_pedido_itens_pedido FOREIGN KEY (pedido_id) REFERENCES pedidos (id)
);

CREATE INDEX idx_pedido_itens_pedido_id ON pedido_itens (pedido_id);
