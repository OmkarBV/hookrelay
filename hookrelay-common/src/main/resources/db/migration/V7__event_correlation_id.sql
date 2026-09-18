-- A correlation id spanning ingestion through final delivery (Phase 8).
-- Backfilled with a fresh random value per existing row so the column can be
-- NOT NULL from the start rather than needing every reader to handle null.
ALTER TABLE event ADD COLUMN correlation_id VARCHAR(64);
UPDATE event SET correlation_id = gen_random_uuid()::text WHERE correlation_id IS NULL;
ALTER TABLE event ALTER COLUMN correlation_id SET NOT NULL;
