-- #82: the anchor check (ADR-0011 §16). Forward-only.
--
-- The anchor check hashes an operator-chosen candidate word, keys it under the pepper and compares it with the value a
-- constant slot holds: a dictionary lookup, so every query is budgeted per caller and day and recorded. A caller is a
-- fixed name ('operator' today; the agent token, #84, adds its own), never an install. Neither table names an install.

-- Candidates spent per caller and UTC day, refused ones included. Kept for the ingest ledger's 7 days.
CREATE TABLE anchor_check_budget (
    caller text NOT NULL CHECK (caller ~ '^[a-z][a-z0-9:_-]{0,63}$'),
    day date NOT NULL,
    candidates integer NOT NULL DEFAULT 0 CHECK (candidates >= 0),
    PRIMARY KEY (caller, day)
);

-- One row per query: the caller, the day, the cluster variant and slot asked about, how many candidates, and the
-- answer class. No text is stored, neither a candidate nor the text a matched slot holds (Sol rounds 1 and 2, S2): a
-- constant slot can hold a customer's value (ADR-0011 risks 5 and 12), so a stored match could be a stored name. Kept
-- 30 days.
CREATE TABLE anchor_checks (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    caller text NOT NULL CHECK (caller ~ '^[a-z][a-z0-9:_-]{0,63}$'),
    day date NOT NULL,
    fingerprint char(64) NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    platform_app_version text NOT NULL,
    hash_domain integer NOT NULL CHECK (hash_domain > 0),
    filter_rev integer NOT NULL CHECK (filter_rev > 0),
    layout char(64) NOT NULL CHECK (layout ~ '^[0-9a-f]{64}$'),
    slot text NOT NULL CHECK (slot ~ '^[A-Za-z0-9/]+$' AND length(slot) <= 512),
    candidates integer NOT NULL CHECK (candidates BETWEEN 1 AND 64),
    result text NOT NULL CHECK (result IN ('match', 'no_answer', 'refused', 'budget_exhausted'))
);
CREATE INDEX anchor_checks_day_idx ON anchor_checks (day);
