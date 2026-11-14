-- #81: the contributor tier (ADR-0011 §15). Forward-only.
--
-- A grant lets one install send contributor data (confirmations, role tags, gated words, quarantine items) and read
-- slot classes. It names the install, as authentication and quotas do; nothing a contributor sends does. A grant
-- lasts 30 UTC days; a revoked one stays (it refuses a new request) until the install withdraws or the operator
-- clears it. Contributor status is separate from installs.trusted and never permits an envelope upload.
CREATE TABLE contributor_grants (
    grant_id uuid PRIMARY KEY,
    install_id uuid NOT NULL REFERENCES installs,
    granted_day date NOT NULL,
    expires_day date NOT NULL CHECK (expires_day > granted_day),
    revoked_day date NULL
);
CREATE INDEX contributor_grants_install_idx ON contributor_grants (install_id);

-- The audit log of slot-class, gate and word reads: which grant asked about which cluster variant, per UTC day. It
-- names the grant, never the install; it goes with the grant (withdrawal, data deletion) or after 30 days.
CREATE TABLE contributor_reads (
    grant_id uuid NOT NULL REFERENCES contributor_grants ON DELETE CASCADE,
    day date NOT NULL,
    kind text NOT NULL CHECK (kind IN ('classes', 'gates', 'words')),
    fingerprint char(64) NOT NULL,
    layout char(64) NOT NULL,
    items integer NOT NULL CHECK (items >= 0)
);
CREATE INDEX contributor_reads_grant_idx ON contributor_reads (grant_id);
CREATE INDEX contributor_reads_day_idx ON contributor_reads (day);

-- Per-install contributor quotas on the daily ledger row, which a data deletion keeps (#79), so a deletion and a new
-- grant cannot reset a day's budget.
ALTER TABLE ingest_ledger ADD COLUMN contributor_reads integer NOT NULL DEFAULT 0,
    ADD COLUMN contributor_reviews integer NOT NULL DEFAULT 0,
    ADD COLUMN contributor_words integer NOT NULL DEFAULT 0,
    ADD COLUMN contributor_quarantine integer NOT NULL DEFAULT 0;

-- A contributor's confirmation that a constant slot holds the app's own wording: the slot's keyed word hash (#85),
-- never the word. Keyed by an epoch tag whose object is the whole row identity (#86), so one install's confirmations
-- of two slots carry unrelated tags, and its two confirmations of one value in one epoch are one row.
CREATE TABLE slot_confirmations (
    fingerprint char(64) NOT NULL REFERENCES clusters ON DELETE CASCADE,
    platform_app_version text NOT NULL,
    hash_domain integer NOT NULL CHECK (hash_domain > 0),
    filter_rev integer NOT NULL CHECK (filter_rev > 0),
    layout char(64) NOT NULL CHECK (layout ~ '^[0-9a-f]{64}$'),
    slot text NOT NULL CHECK (slot ~ '^[A-Za-z0-9/]+$' AND length(slot) <= 512),
    value char(16) NOT NULL CHECK (value ~ '^[0-9a-f]{16}$'),
    epoch integer NOT NULL,
    install_tag char(64) NOT NULL,
    trusted boolean NOT NULL,
    last_day date NOT NULL,
    PRIMARY KEY (fingerprint, platform_app_version, hash_domain, filter_rev, layout, slot, epoch, value, install_tag)
);
CREATE INDEX slot_confirmations_tag_idx ON slot_confirmations (install_tag);

-- What a field means, from a closed list, never a value. Tagged like confirmations.
CREATE TABLE slot_roles (
    fingerprint char(64) NOT NULL REFERENCES clusters ON DELETE CASCADE,
    platform_app_version text NOT NULL,
    hash_domain integer NOT NULL CHECK (hash_domain > 0),
    filter_rev integer NOT NULL CHECK (filter_rev > 0),
    layout char(64) NOT NULL CHECK (layout ~ '^[0-9a-f]{64}$'),
    slot text NOT NULL CHECK (slot ~ '^[A-Za-z0-9/]+$' AND length(slot) <= 512),
    role text NOT NULL CHECK (role IN ('total_pay', 'tip', 'base_pay', 'deadline', 'distance', 'item_count',
        'customer_info', 'store_name', 'other')),
    epoch integer NOT NULL,
    install_tag char(64) NOT NULL,
    trusted boolean NOT NULL,
    last_day date NOT NULL,
    PRIMARY KEY (fingerprint, platform_app_version, hash_domain, filter_rev, layout, slot, epoch, role, install_tag)
);
CREATE INDEX slot_roles_tag_idx ON slot_roles (install_tag);

-- Sol S6: a release built on day D holds the slot rows as they stood at the end of D-1, so an upload reaches a
-- release the next day at the earliest. Each slot sighting therefore keeps the receipt day it was first written
-- (first_received), the receipt day of its latest write (received_day), and its last_day as it stood before that
-- receipt day (prev_last_day). A row's state before D is: absent if first_received >= D, else prev_last_day when
-- received_day >= D, else last_day. A presence row's last_day is the newest of its values', and each value row keeps
-- its own, so the state before D keeps a value behind every presence (Sol N3). A row written before V10 has no
-- recorded receipt day; an observation day would understate it (a row received today can describe yesterday), so it
-- takes the migration day (UTC) as both receipt days and first appears in the next day's release (round 2, S6).
ALTER TABLE slot_sightings ADD COLUMN first_received date, ADD COLUMN received_day date, ADD COLUMN prev_last_day date NULL;
UPDATE slot_sightings SET first_received = (now() AT TIME ZONE 'UTC')::date, received_day = (now() AT TIME ZONE 'UTC')::date;
ALTER TABLE slot_sightings ALTER COLUMN first_received SET NOT NULL, ALTER COLUMN received_day SET NOT NULL;

-- The day's released slot evidence: a copy of the slot_sightings rows counted as they stood at the end of the previous
-- day, taken in one statement when the day's release is built, served on that UTC day only and deleted after it. Classes read by contributors come from here,
-- with the reader's own rows left out (found through its epoch tags), never from live rows. Erasure deletes an
-- install's copied rows through the same tags as its slot sightings.
CREATE TABLE slot_release_days (
    release_day date PRIMARY KEY
);
CREATE TABLE slot_release (
    release_day date NOT NULL REFERENCES slot_release_days ON DELETE CASCADE,
    fingerprint char(64) NOT NULL,
    platform_app_version text NOT NULL,
    hash_domain integer NOT NULL,
    filter_rev integer NOT NULL,
    layout char(64) NOT NULL,
    slot text NOT NULL,
    value text NOT NULL,
    epoch integer NOT NULL,
    install_tag char(64) NOT NULL,
    PRIMARY KEY (release_day, fingerprint, platform_app_version, hash_domain, filter_rev, layout, slot, epoch, value, install_tag)
);
CREATE INDEX slot_release_tag_idx ON slot_release (install_tag);

-- A word that passed the gate: the app's own wording on a constant slot, waiting for the operator, who approves it
-- into the working vocabulary through /ops/vocabulary/resolve (source 'contributor') or rejects it. Names no install.
CREATE TABLE contributor_words (
    fingerprint char(64) NOT NULL REFERENCES clusters ON DELETE CASCADE,
    platform_app_version text NOT NULL,
    hash_domain integer NOT NULL CHECK (hash_domain > 0),
    filter_rev integer NOT NULL CHECK (filter_rev > 0),
    layout char(64) NOT NULL,
    slot text NOT NULL,
    value char(16) NOT NULL CHECK (value ~ '^[0-9a-f]{16}$'),
    clear_text text NOT NULL CHECK (length(clear_text) BETWEEN 1 AND 128),
    received_day date NOT NULL,
    PRIMARY KEY (fingerprint, platform_app_version, hash_domain, filter_rev, layout, slot, value)
);

-- "Ask the developer": the outbound artifact, encrypted on receipt to the operator's public key (the server holds
-- no key that opens it), expiring at the start of expires_day (at most 72 hours after receipt). Read by the
-- quarantine views only; never clustered, counted or drafted from. backup.sh dumps no row of it. Tagged by an epoch
-- tag whose object is the item id, so erasure finds an install's items.
CREATE TABLE quarantine_items (
    id uuid PRIMARY KEY,
    epoch integer NOT NULL,
    install_tag char(64) NOT NULL,
    expires_day date NOT NULL,
    note text NULL CHECK (note IN ('might_contain_customer', 'might_contain_my_info', 'unsure_what_screen')),
    artifact bytea NOT NULL
);
CREATE INDEX quarantine_items_tag_idx ON quarantine_items (install_tag);
