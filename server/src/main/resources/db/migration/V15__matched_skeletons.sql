-- #69 (gig.log#1573): matched skeletons. Forward-only.
--
-- A matched skeleton (`uinode.matched.v1`) is a screen a recognition rule matched while the rule was failing to read
-- it on the phone, tagged with the rule's id. It is stored apart from the cluster tables: it never upserts `clusters`
-- (a known screen stays out of the UNKNOWN catalogue and never moves a cluster's last_seen_day), and it never feeds
-- token sightings, slot sightings, vocabulary, the anchor check or the contributor release. The link to the rule's
-- older layouts is `clusters.resolved_rule_id`, set by the operator.
--
-- `matched_samples` names no install, like `cluster_samples`: at most 5 per (rule, fingerprint, app version), one per
-- receipt day (ingest enforces the five). `skeleton` is keyed at rest (#85). `purge_after` is the observation day
-- (clamped to the receipt day) + 30, so a sample lives no longer than its sightings and needs no hash-expiry rewrite.
CREATE TABLE matched_samples (
    rule_id text NOT NULL CHECK (length(rule_id) <= 96 AND rule_id ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*){1,4}$'),
    platform text NOT NULL,
    fingerprint char(64) NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    platform_app_version text NOT NULL,
    received_day date NOT NULL,
    skeleton jsonb NOT NULL,
    hash_domain integer NOT NULL CHECK (hash_domain > 0),
    filter_rev integer NOT NULL CHECK (filter_rev > 0),
    purge_after date NOT NULL,
    PRIMARY KEY (rule_id, fingerprint, platform_app_version, received_day)
);
CREATE INDEX matched_samples_rule_idx ON matched_samples (rule_id, received_day DESC);
CREATE INDEX matched_samples_purge_idx ON matched_samples (purge_after);

-- Presence only, epoch-tagged (#86): the tag's object is `<rule_id>:<fingerprint>`. No counts_from, like
-- cluster_sightings_v5.
CREATE TABLE matched_sightings (
    rule_id text NOT NULL,
    fingerprint char(64) NOT NULL,
    epoch integer NOT NULL,
    install_tag char(64) NOT NULL,
    trusted boolean NOT NULL,
    day date NOT NULL,
    platform_app_version text NOT NULL,
    hash_domain integer NOT NULL CHECK (hash_domain > 0),
    filter_rev integer NOT NULL CHECK (filter_rev > 0),
    PRIMARY KEY (rule_id, fingerprint, install_tag, day, platform_app_version, hash_domain, filter_rev)
);
CREATE INDEX matched_sightings_day_idx ON matched_sightings (day);
CREATE INDEX matched_sightings_tag_idx ON matched_sightings (install_tag);

-- The slot evidence of matched skeletons: the slot_sightings columns and CHECKs (V9, V10's receipt columns, which the
-- one shared writer fills), plus the rule id; the tag's object is `<rule_id>:` before the slot identity. No foreign
-- key to clusters: a matched layout need not be a cluster.
CREATE TABLE matched_slot_sightings (LIKE slot_sightings INCLUDING DEFAULTS INCLUDING CONSTRAINTS);
ALTER TABLE matched_slot_sightings ADD COLUMN rule_id text NOT NULL,
    ADD PRIMARY KEY (rule_id, fingerprint, platform_app_version, hash_domain, filter_rev, layout, slot, epoch, value, install_tag);
CREATE INDEX matched_slot_sightings_tag_idx ON matched_slot_sightings (install_tag);
CREATE INDEX matched_slot_sightings_last_day_idx ON matched_slot_sightings (last_day);
