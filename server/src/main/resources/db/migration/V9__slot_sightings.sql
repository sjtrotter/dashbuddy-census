-- #80: per-slot constancy (ADR-0011 §14). Forward-only.
--
-- Token sightings say which installs saw a word, not where on the screen, so they cannot tell a slot's chrome from a
-- value. A slot sighting is presence only: within one epoch, an install saw this value in this slot of this cluster
-- variant. Like every content table since #86 it names no install: `install_tag` is an epoch tag whose object is the
-- whole row identity (cluster, domain, revision, slot, value, app version), so one install's rows for two values carry
-- unrelated tags and a dump does not show which values one install saw together. A second row per slot, value
-- 'present', counts the installs that saw the slot at all. `value` is a keyed word hash (#85) or a marker.
-- Retention is the cluster sightings' (Retention.clusterSightingsDays) on `last_day`; trust and the quarantine mark
-- travel on the row as on token sightings.
CREATE TABLE slot_sightings (
    fingerprint char(64) NOT NULL REFERENCES clusters ON DELETE CASCADE,
    platform_app_version text NOT NULL,
    hash_domain integer NOT NULL CHECK (hash_domain > 0),
    filter_rev integer NOT NULL CHECK (filter_rev > 0),
    slot text NOT NULL CHECK (slot ~ '^[A-Za-z0-9/]+$' AND length(slot) <= 512),
    value text NOT NULL CHECK (value ~ '^[0-9a-f]{16}$' OR value IN ('present', 'withheld', 'shape', 'unhashed')),
    epoch integer NOT NULL,
    install_tag char(64) NOT NULL,
    trusted boolean NOT NULL,
    counts_from date NULL,
    last_day date NOT NULL,
    PRIMARY KEY (fingerprint, platform_app_version, hash_domain, filter_rev, slot, epoch, value, install_tag)
);
CREATE INDEX slot_sightings_tag_idx ON slot_sightings (install_tag);
CREATE INDEX slot_sightings_last_day_idx ON slot_sightings (last_day);
