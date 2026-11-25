CREATE TABLE links (code varchar(32) PRIMARY KEY, url text NOT NULL, expires_at timestamptz, active boolean NOT NULL DEFAULT true, clicks bigint NOT NULL DEFAULT 0);
