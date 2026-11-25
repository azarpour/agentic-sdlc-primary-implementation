CREATE TABLE idempotency_keys (
    idem_key varchar(128) PRIMARY KEY,
    payload_hash char(64) NOT NULL,
    code varchar(32) NOT NULL REFERENCES links(code),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_idempotency_created_at ON idempotency_keys(created_at);

ALTER TABLE links ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();

CREATE INDEX idx_links_expires_at ON links(expires_at);
