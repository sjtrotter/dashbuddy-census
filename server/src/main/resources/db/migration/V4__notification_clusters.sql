ALTER TABLE clusters ADD COLUMN kind text NOT NULL DEFAULT 'screen'
    CHECK (kind IN ('screen', 'notification'));
