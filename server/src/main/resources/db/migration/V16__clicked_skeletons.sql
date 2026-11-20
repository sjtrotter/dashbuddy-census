-- #87 (gig.log#1634): clicked skeletons. Forward-only.
--
-- A clicked skeleton (`uinode.clicked.v1`) is the screen of the window a dasher tapped, built and filtered on the phone
-- like an UNKNOWN skeleton, plus the tapped button as an index path into that screen (`tap.ownerPath`). It is stored
-- apart from the cluster tables: it never upserts `clusters` and never feeds token sightings, slot sightings,
-- vocabulary, the anchor check or the contributor release. A target is one owner path in one raw layout
-- (`SlotWalk.layout`) of one fingerprint: the fingerprint splices anonymous wrappers away, so a path names one node
-- only within one layout.
--
-- `clicked_samples` names no install, like `cluster_samples` and `matched_samples`: one sample per (target, app
-- version, observation day), the first that arrives. `skeleton` is keyed at rest (#85). `purge_after` is the
-- observation day (clamped to the receipt day) + 30, so a sample lives no longer than its sightings and needs no
-- hash-expiry rewrite.
CREATE TABLE clicked_samples (
    platform text NOT NULL,
    fingerprint char(64) NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    layout char(64) NOT NULL CHECK (layout ~ '^[0-9a-f]{64}$'),
    owner_path text NOT NULL CHECK (length(owner_path) <= 324 AND owner_path ~ '^root(/(0|[1-9][0-9]{0,3})){0,64}$'),
    platform_app_version text NOT NULL,
    day date NOT NULL,
    received_day date NOT NULL,
    skeleton jsonb NOT NULL,
    hash_domain integer NOT NULL CHECK (hash_domain > 0),
    filter_rev integer NOT NULL CHECK (filter_rev > 0),
    purge_after date NOT NULL,
    PRIMARY KEY (fingerprint, layout, owner_path, platform_app_version, day)
);
CREATE INDEX clicked_samples_purge_idx ON clicked_samples (purge_after);
CREATE INDEX clicked_samples_received_idx ON clicked_samples (received_day DESC);

-- Presence only, epoch-tagged (#86): the tag's object is `<fingerprint>:<layout>:<owner_path>`. One row per tag,
-- target and observation day (and the variant columns the filter floor and the operator view read), like
-- matched_sightings. No counts_from, like cluster_sightings_v5.
CREATE TABLE clicked_sightings (
    fingerprint char(64) NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    layout char(64) NOT NULL CHECK (layout ~ '^[0-9a-f]{64}$'),
    owner_path text NOT NULL CHECK (length(owner_path) <= 324 AND owner_path ~ '^root(/(0|[1-9][0-9]{0,3})){0,64}$'),
    epoch integer NOT NULL,
    install_tag char(64) NOT NULL,
    trusted boolean NOT NULL,
    day date NOT NULL,
    platform_app_version text NOT NULL,
    hash_domain integer NOT NULL CHECK (hash_domain > 0),
    filter_rev integer NOT NULL CHECK (filter_rev > 0),
    PRIMARY KEY (fingerprint, layout, owner_path, install_tag, day, platform_app_version, hash_domain, filter_rev)
);
CREATE INDEX clicked_sightings_day_idx ON clicked_sightings (day);
CREATE INDEX clicked_sightings_tag_idx ON clicked_sightings (install_tag);
