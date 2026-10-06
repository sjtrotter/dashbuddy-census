-- Additive replacement storage: legacy rows cannot be assigned trustworthy provenance.
-- Startup performs bounded, repeatable cutover before accepting HTTP.
CREATE TABLE IF NOT EXISTS token_sightings_v5 (
    token_hash char(16) NOT NULL,
    install_id uuid NOT NULL REFERENCES installs,
    first_day date NOT NULL,
    last_day date NOT NULL,
    kind text NOT NULL,
    hash_domain integer NOT NULL CHECK (hash_domain > 0),
    filter_rev integer NOT NULL CHECK (filter_rev > 0),
    PRIMARY KEY (hash_domain, token_hash, filter_rev, install_id)
);
CREATE INDEX IF NOT EXISTS token_sightings_v5_last_day_idx ON token_sightings_v5(last_day);
CREATE TABLE IF NOT EXISTS cluster_sightings_v5 (
    fingerprint char(64) NOT NULL REFERENCES clusters,
    install_id uuid NOT NULL REFERENCES installs,
    day date NOT NULL,
    platform_app_version text NOT NULL,
    count integer NOT NULL DEFAULT 1,
    hash_domain integer NOT NULL CHECK (hash_domain > 0),
    filter_rev integer NOT NULL CHECK (filter_rev > 0),
    PRIMARY KEY (fingerprint, install_id, day, platform_app_version, hash_domain, filter_rev)
);
CREATE INDEX IF NOT EXISTS cluster_sightings_v5_day_idx ON cluster_sightings_v5(day);
CREATE INDEX IF NOT EXISTS cluster_sightings_v5_revision_idx ON cluster_sightings_v5(filter_rev);
CREATE TABLE IF NOT EXISTS vocabulary_v5 (
    token_hash char(16) NOT NULL,
    kind text NOT NULL,
    distinct_installs_at_promotion integer NOT NULL,
    promoted_day date NOT NULL,
    clear_text text NULL,
    unblinded_day date NULL,
    source text NULL,
    status text NOT NULL CHECK (status IN ('queued', 'unblinded', 'rejected', 'shipped')),
    hash_domain integer NOT NULL CHECK (hash_domain > 0),
    filter_rev integer NOT NULL CHECK (filter_rev > 0),
    PRIMARY KEY (hash_domain, token_hash, filter_rev)
);
ALTER TABLE cluster_samples ADD COLUMN IF NOT EXISTS hash_domain integer NULL;
ALTER TABLE cluster_samples ADD COLUMN IF NOT EXISTS filter_rev integer NULL;
ALTER TABLE cluster_samples ADD COLUMN IF NOT EXISTS purge_after date NULL;
ALTER TABLE clusters ADD COLUMN IF NOT EXISTS resolved_day date NULL;
CREATE INDEX IF NOT EXISTS cluster_samples_deadline_idx ON cluster_samples(purge_after);
CREATE INDEX IF NOT EXISTS cluster_samples_provenance_idx ON cluster_samples(hash_domain, filter_rev);
CREATE TABLE IF NOT EXISTS filter_floor (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    min_filter_rev integer NOT NULL CHECK (min_filter_rev >= 1),
    changed_day date NOT NULL
);
-- Refuse a pre-existing incompatible definition instead of silently adopting it.
DO $$
DECLARE item record;
BEGIN
    FOR item IN SELECT * FROM (VALUES
        ('token_sightings_v5', ARRAY['hash_domain','token_hash','filter_rev','install_id']),
        ('cluster_sightings_v5', ARRAY['fingerprint','install_id','day','platform_app_version','hash_domain','filter_rev']),
        ('vocabulary_v5', ARRAY['hash_domain','token_hash','filter_rev']),
        ('filter_floor', ARRAY['singleton'])
    ) AS expected(tab, cols) LOOP
        IF (SELECT array_agg(a.attname::text ORDER BY k.ord)
            FROM pg_constraint c CROSS JOIN LATERAL unnest(c.conkey) WITH ORDINALITY k(num,ord)
            JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attnum=k.num
            WHERE c.conrelid=item.tab::regclass AND c.contype='p') IS DISTINCT FROM item.cols THEN
            RAISE EXCEPTION 'Unexpected lifecycle table definition';
        END IF;
    END LOOP;
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=current_schema()
        AND ((table_name IN ('token_sightings_v5','cluster_sightings_v5','vocabulary_v5')
            AND column_name IN ('hash_domain','filter_rev') AND (data_type <> 'integer' OR is_nullable <> 'NO'))
        OR (table_name='cluster_samples' AND column_name IN ('hash_domain','filter_rev') AND data_type <> 'integer')
        OR (table_name='cluster_samples' AND column_name='purge_after' AND data_type <> 'date')
        OR (table_name='clusters' AND column_name='resolved_day' AND data_type <> 'date'))) THEN
        RAISE EXCEPTION 'Unexpected lifecycle column definition';
    END IF;
    IF EXISTS (
        SELECT 1 FROM information_schema.columns old
        LEFT JOIN information_schema.columns fresh ON fresh.table_schema=old.table_schema
            AND fresh.table_name=old.table_name || '_v5' AND fresh.column_name=old.column_name
        WHERE old.table_schema=current_schema() AND old.table_name IN ('token_sightings','cluster_sightings','vocabulary')
        AND (fresh.column_name IS NULL OR fresh.data_type IS DISTINCT FROM old.data_type
            OR fresh.character_maximum_length IS DISTINCT FROM old.character_maximum_length
            OR fresh.is_nullable IS DISTINCT FROM old.is_nullable
            OR fresh.column_default IS DISTINCT FROM old.column_default)
    ) THEN RAISE EXCEPTION 'Unexpected lifecycle evidence definition'; END IF;
    FOR item IN SELECT * FROM (VALUES ('token_sightings_v5'),('cluster_sightings_v5'),('vocabulary_v5')) AS expected(tab) LOOP
        IF (SELECT count(*) FROM pg_index WHERE indrelid=item.tab::regclass AND indisunique) <> 1 THEN
            RAISE EXCEPTION 'Unexpected lifecycle uniqueness definition';
        END IF;
    END LOOP;
    FOR item IN SELECT * FROM (VALUES
        ('token_sightings_v5_last_day_idx', ARRAY['last_day']),
        ('cluster_sightings_v5_day_idx', ARRAY['day']),
        ('cluster_sightings_v5_revision_idx', ARRAY['filter_rev']),
        ('cluster_samples_deadline_idx', ARRAY['purge_after']),
        ('cluster_samples_provenance_idx', ARRAY['hash_domain','filter_rev'])
    ) AS expected(idx,cols) LOOP
        IF (SELECT array_agg(a.attname::text ORDER BY k.ord) FROM pg_index i
            CROSS JOIN LATERAL unnest(i.indkey) WITH ORDINALITY k(num,ord)
            JOIN pg_attribute a ON a.attrelid=i.indrelid AND a.attnum=k.num
            WHERE i.indexrelid=item.idx::regclass AND i.indisvalid AND i.indpred IS NULL) IS DISTINCT FROM item.cols THEN
            RAISE EXCEPTION 'Unexpected lifecycle index definition';
        END IF;
    END LOOP;
    FOR item IN SELECT * FROM (VALUES
        ('token_sightings_v5', 'hash_domain'), ('token_sightings_v5', 'filter_rev'),
        ('cluster_sightings_v5', 'hash_domain'), ('cluster_sightings_v5', 'filter_rev'),
        ('vocabulary_v5', 'hash_domain'), ('vocabulary_v5', 'filter_rev')
    ) AS expected(tab, col) LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid=item.tab::regclass AND contype='c'
            AND pg_get_constraintdef(oid) = 'CHECK ((' || item.col || ' > 0))') THEN
            RAISE EXCEPTION 'Unexpected lifecycle check definition';
        END IF;
    END LOOP;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='filter_floor'::regclass
        AND pg_get_constraintdef(oid)='CHECK (singleton)') OR NOT EXISTS
        (SELECT 1 FROM pg_constraint WHERE conrelid='filter_floor'::regclass
        AND pg_get_constraintdef(oid)='CHECK ((min_filter_rev >= 1))') THEN
        RAISE EXCEPTION 'Unexpected floor definition';
    END IF;
    FOR item IN SELECT * FROM (VALUES
        ('token_sightings_v5','installs','install_id'),
        ('cluster_sightings_v5','installs','install_id'),
        ('cluster_sightings_v5','clusters','fingerprint')
    ) AS expected(tab, parent, col) LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_constraint c JOIN pg_attribute a
            ON a.attrelid=c.conrelid AND c.conkey=ARRAY[a.attnum]
            WHERE c.conrelid=item.tab::regclass AND c.contype='f' AND c.confrelid=item.parent::regclass
            AND a.attname=item.col) THEN
            RAISE EXCEPTION 'Unexpected lifecycle foreign key definition';
        END IF;
    END LOOP;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='vocabulary_v5'::regclass AND contype='c'
        AND pg_get_constraintdef(oid) LIKE '%queued%unblinded%rejected%shipped%') THEN
        RAISE EXCEPTION 'Unexpected vocabulary status definition';
    END IF;
END $$;
