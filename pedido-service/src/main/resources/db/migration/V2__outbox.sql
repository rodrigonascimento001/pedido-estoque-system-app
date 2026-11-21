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
