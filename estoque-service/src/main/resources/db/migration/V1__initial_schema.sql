CREATE TABLE estoque (
    produto_id UUID NOT NULL PRIMARY KEY,
    quantidade_disponivel INTEGER NOT NULL,
    versao BIGINT NOT NULL
);
