-- #86: the lifecycle tables' shape after V8 (epoch pseudonyms), checked at the end of V8 and at every startup by the
-- filter bootstrap. It replaces V5's check, which pinned install_id into the sighting keys and compared the _v5
-- tables with the pre-V5 tables V8 drops. Not a Flyway migration: it lives outside db/migration on purpose.
-- Refuse a pre-existing or altered incompatible definition instead of silently adopting it.
DO $$
DECLARE item record;
BEGIN
    FOR item IN SELECT * FROM (VALUES
        ('token_sightings_v5', ARRAY['hash_domain','token_hash','filter_rev','epoch','install_tag']),
        ('cluster_sightings_v5', ARRAY['fingerprint','install_tag','day','platform_app_version','hash_domain','filter_rev']),
        ('health_daily', ARRAY['install_tag','day','platform','platform_app_version']),
        ('vocabulary_v5', ARRAY['hash_domain','token_hash','filter_rev']),
        ('epoch_keys', ARRAY['epoch']),
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
        OR (table_name IN ('token_sightings_v5','cluster_sightings_v5','health_daily')
            AND ((column_name='epoch' AND (data_type <> 'integer' OR is_nullable <> 'NO'))
            OR (column_name='install_tag' AND (data_type <> 'character' OR character_maximum_length <> 64 OR is_nullable <> 'NO'))))
        OR (table_name IN ('token_sightings_v5','cluster_sightings_v5')
            AND column_name='trusted' AND (data_type <> 'boolean' OR is_nullable <> 'NO'))
        OR (table_name='token_sightings_v5' AND column_name='counts_from' AND data_type <> 'date')
        OR (table_name='cluster_samples' AND column_name IN ('hash_domain','filter_rev') AND data_type <> 'integer')
        OR (table_name='cluster_samples' AND column_name='purge_after' AND data_type <> 'date')
        OR (table_name='clusters' AND column_name='resolved_day' AND data_type <> 'date'))) THEN
        RAISE EXCEPTION 'Unexpected lifecycle column definition';
    END IF;
    -- No content table names an install (#86); the install id stays in installs, nonces, the ledger and the trusted
    -- envelopes (the operator's own devices).
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=current_schema() AND column_name='install_id'
        AND table_name NOT IN ('installs','nonces','ingest_ledger','trusted_envelopes')) THEN
        RAISE EXCEPTION 'Unexpected install id column';
    END IF;
    FOR item IN SELECT * FROM (VALUES ('token_sightings_v5'),('cluster_sightings_v5'),('vocabulary_v5'),('health_daily')) AS expected(tab) LOOP
        IF (SELECT count(*) FROM pg_index WHERE indrelid=item.tab::regclass AND indisunique) <> 1 THEN
            RAISE EXCEPTION 'Unexpected lifecycle uniqueness definition';
        END IF;
    END LOOP;
    FOR item IN SELECT * FROM (VALUES
        ('token_sightings_v5_last_day_idx', ARRAY['last_day']),
        ('token_sightings_v5_tag_idx', ARRAY['install_tag']),
        ('cluster_sightings_v5_day_idx', ARRAY['day']),
        ('cluster_sightings_v5_revision_idx', ARRAY['filter_rev']),
        ('cluster_sightings_v5_tag_idx', ARRAY['install_tag']),
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
    IF NOT EXISTS (SELECT 1 FROM pg_constraint c JOIN pg_attribute a
        ON a.attrelid=c.conrelid AND c.conkey=ARRAY[a.attnum]
        WHERE c.conrelid='cluster_sightings_v5'::regclass AND c.contype='f' AND c.confrelid='clusters'::regclass
        AND a.attname='fingerprint') THEN
        RAISE EXCEPTION 'Unexpected lifecycle foreign key definition';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='vocabulary_v5'::regclass AND contype='c'
        AND pg_get_constraintdef(oid) LIKE '%queued%unblinded%rejected%shipped%') THEN
        RAISE EXCEPTION 'Unexpected vocabulary status definition';
    END IF;
END $$;
