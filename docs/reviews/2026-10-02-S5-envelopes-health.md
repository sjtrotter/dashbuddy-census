# Review record — S5 trusted envelopes + health reports + alarms (2026-10-02)

Builder: codex `gpt-6-astra` (high, workspace-write) from the fable-written spec `S5-SPEC.md`; the session built/tested under
Docker after the hand-off — green on the first build (81 tests, Testcontainers Postgres 16).

| Round | Outcome |
|---|---|
| 1 — Astra | Astra: **DO NOT MERGE** (3 P1, 5 P2) — P1 envelope strings outside payload/windowTitle (keys, pipelineId/ruleId/classificationName, metadata, windowContext) stored unscanned → whole-element key+value scan + per-field grammars/allowlists; P1 alarm WARN echoed client `platform`/`version` → validated at ingest (policy platforms, numeric versions) + the sink renders only grammar-matching tokens; P1 one failed purge sweep stopped the rest → per-sweep isolation; P2 unbounded purge DELETEs → 1 000-row ctid batches, ≤ 50/run, ensureActive between; P2 alarm failure 500'd a committed health batch → isolated, dedupe recorded after delivery; P2 dedupe key lacked platform → added; P2 today's rule-death input split per version → aggregated per install/platform/day like history; P2 silence measured client calendar days → 48 h server-side elapsed clock (process-local, restart fails toward no alarm) ∧ stored-day check. |
