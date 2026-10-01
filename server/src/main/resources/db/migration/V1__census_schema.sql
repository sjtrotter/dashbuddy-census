-- #1157 S1. Retention: token_sightings 30 days, cluster_sightings 90 days,
-- trusted_envelopes 30 days, health_daily 180 days, ingest_ledger 7 days.
-- Backups: 14 days. Aggregate/catalog retention and pruning jobs arrive later.
-- JSON/text columns must never hold identifiers or timestamps finer than a day — enforced by the ingest validators (S3), stated here
-- a hash with zero rows does not exist; no IP, no timestamp finer than a day except nonces/revocation.

CREATE TABLE installs (
    install_id uuid PRIMARY KEY,
    key_hash char(64) NOT NULL,
    created_day date NOT NULL,
    last_seen_day date NOT NULL,
    trusted boolean NOT NULL DEFAULT false,
    revoked_at timestamptz NULL,
    attested_verdict text NULL,
    last_app_version text NULL,
    metro_cell text NULL
);

CREATE TABLE clusters (
    fingerprint char(64) PRIMARY KEY,
    platform text NOT NULL,
    family_key text NULL,
    first_seen_day date NOT NULL,
    last_seen_day date NOT NULL,
    status text NOT NULL DEFAULT 'new' CHECK (status IN ('new', 'triaged', 'drafted', 'resolved', 'ignored')),
    resolved_rule_id text NULL,
    notes text NULL
);

-- At most 5 samples per (fingerprint, platform_app_version) is enforced by ingest, not the schema.
CREATE TABLE cluster_samples (
    fingerprint char(64) REFERENCES clusters,
    platform_app_version text NOT NULL,
    received_day date NOT NULL,
    skeleton jsonb NOT NULL,
    PRIMARY KEY (fingerprint, platform_app_version, received_day)
);

CREATE TABLE cluster_sightings (
    fingerprint char(64) REFERENCES clusters,
    install_id uuid REFERENCES installs,
    day date NOT NULL,
    platform_app_version text NOT NULL,
    count integer NOT NULL DEFAULT 1,
    PRIMARY KEY (fingerprint, install_id, day, platform_app_version)
);

CREATE TABLE token_sightings (
    token_hash char(16) NOT NULL,
    install_id uuid REFERENCES installs,
    first_day date NOT NULL,
    last_day date NOT NULL,
    kind text NOT NULL,
    PRIMARY KEY (token_hash, install_id)
);
CREATE INDEX token_sightings_last_day_idx ON token_sightings (last_day);

CREATE TABLE vocabulary (
    token_hash char(16) PRIMARY KEY,
    kind text NOT NULL,
    distinct_installs_at_promotion integer NOT NULL,
    promoted_day date NOT NULL,
    clear_text text NULL,
    unblinded_day date NULL,
    source text NULL,
    status text NOT NULL CHECK (status IN ('queued', 'unblinded', 'rejected', 'shipped'))
);

CREATE TABLE trusted_envelopes (
    id bigserial PRIMARY KEY,
    install_id uuid REFERENCES installs,
    fingerprint char(64) NULL REFERENCES clusters,
    envelope jsonb NOT NULL,
    received_day date NOT NULL,
    purge_after date NOT NULL
);

CREATE TABLE health_daily (
    install_id uuid REFERENCES installs,
    day date,
    platform text,
    platform_app_version text,
    admitted integer NOT NULL,
    unknown integer NOT NULL,
    trips integer NOT NULL,
    ruleset_version text NULL,
    rule_counts jsonb NOT NULL,
    PRIMARY KEY (install_id, day, platform, platform_app_version)
);

CREATE TABLE health_fleet_daily (
    day date,
    platform text,
    platform_app_version text,
    installs_reporting integer NOT NULL,
    admitted bigint NOT NULL,
    unknown bigint NOT NULL,
    rule_counts jsonb NOT NULL,
    PRIMARY KEY (day, platform, platform_app_version)
);

CREATE TABLE ingest_ledger (
    install_id uuid REFERENCES installs,
    day date,
    bytes bigint NOT NULL DEFAULT 0,
    accepted integer NOT NULL DEFAULT 0,
    duplicate integer NOT NULL DEFAULT 0,
    rejected jsonb NOT NULL DEFAULT '{}',
    batch_ids text[] NOT NULL DEFAULT '{}',
    PRIMARY KEY (install_id, day)
);

CREATE TABLE nonces (
    nonce char(32) PRIMARY KEY,
    install_id uuid NULL REFERENCES installs,
    issued_at timestamptz NOT NULL,
    used boolean NOT NULL DEFAULT false
);
CREATE INDEX nonces_issued_at_idx ON nonces (issued_at);
