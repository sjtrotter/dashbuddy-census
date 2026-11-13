-- #80: per-slot constancy (ADR-0011 §14). Forward-only.
--
-- Token sightings say which installs saw a word, not where on the screen, so they cannot tell a slot's chrome from a
-- value. A slot sighting is presence only: within one epoch, an install saw this value in this slot of this cluster
-- variant and raw layout. Like every content table since #86 it names no install: `install_tag` is an epoch tag whose
-- object is the whole row identity (cluster, domain, revision, layout, slot, value, app version), so one install's rows
-- for two values carry unrelated tags and a dump does not show which values one install saw together. A second row
-- per slot, value 'present', counts the installs that saw the slot at all. `value` is a keyed word hash (#85) or a
-- marker. `layout` is the hash of the raw tree as sent, wrappers included, so one slot path names one node (Sol S4).
-- `last_day` is the row's own newest observation day and drives retention (Retention.clusterSightingsDays, Sol S3);
-- `first_received` is the receipt day of the row's first upload, which the release below needs (Sol S1). Trust and the
-- quarantine mark travel on the row as on token sightings.
CREATE TABLE slot_sightings (
    fingerprint char(64) NOT NULL REFERENCES clusters ON DELETE CASCADE,
    platform_app_version text NOT NULL,
    hash_domain integer NOT NULL CHECK (hash_domain > 0),
    filter_rev integer NOT NULL CHECK (filter_rev > 0),
    layout char(64) NOT NULL CHECK (layout ~ '^[0-9a-f]+$'),
    slot text NOT NULL CHECK (slot ~ '^[A-Za-z0-9/]+$' AND length(slot) <= 512),
    value text NOT NULL CHECK (value ~ '^[0-9a-f]{16}$' OR value IN ('present', 'withheld', 'shape', 'unhashed')),
    epoch integer NOT NULL,
    install_tag char(64) NOT NULL,
    trusted boolean NOT NULL,
    counts_from date NULL,
    first_received date NOT NULL,
    last_day date NOT NULL,
    PRIMARY KEY (fingerprint, platform_app_version, hash_domain, filter_rev, layout, slot, epoch, value, install_tag)
);
CREATE INDEX slot_sightings_tag_idx ON slot_sightings (install_tag);
CREATE INDEX slot_sightings_last_day_idx ON slot_sightings (last_day);

-- Sol S1: the app reads classes from a released snapshot, never from live rows, or a guessed upload would flip a class
-- the uploader can read at once. Once a UTC day the lifecycle sweep replaces this table with the per-value install
-- counts of every slot k installs saw, counting only rows received before the release day. It holds counts and keyed
-- hashes, no epoch tag and no install; the endpoint subtracts the asking install's own counted rows before it
-- classifies, so its own uploads never decide its own answer.
CREATE TABLE slot_evidence_released (
    fingerprint char(64) NOT NULL REFERENCES clusters ON DELETE CASCADE,
    platform_app_version text NOT NULL,
    hash_domain integer NOT NULL,
    filter_rev integer NOT NULL,
    layout char(64) NOT NULL,
    slot text NOT NULL,
    epoch integer NOT NULL,
    value text NOT NULL,
    installs integer NOT NULL CHECK (installs > 0),
    PRIMARY KEY (fingerprint, platform_app_version, hash_domain, filter_rev, layout, slot, value)
);

-- When the snapshot above was taken: its day (rows received before it count) and instant (an install that erased its
-- data after it gets no answer until the next release, since its counted rows can no longer be subtracted).
CREATE TABLE slot_release (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    released_day date NOT NULL,
    released_at timestamptz NOT NULL
);
