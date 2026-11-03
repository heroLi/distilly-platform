ALTER TABLE memory_sync_intent ADD COLUMN dispatched_at BIGINT;
ALTER TABLE memory_sync_intent ADD COLUMN receipt_mode VARCHAR(24);
ALTER TABLE memory_sync_intent ADD COLUMN receipt_outcome VARCHAR(16);
ALTER TABLE memory_sync_intent ADD COLUMN error_code VARCHAR(40);
ALTER TABLE memory_sync_intent ADD COLUMN retry_of VARCHAR(36);
-- Offline simulation only; stores projection identifiers/fingerprints, never source text.
CREATE TABLE memory_sync_mock_document (
    persona_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(100) NOT NULL,
    memory_id VARCHAR(36) NOT NULL,
    revision BIGINT NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    PRIMARY KEY(persona_id,user_id,memory_id)
);
CREATE TABLE memory_sync_mock_receipt (
    operation_id VARCHAR(36) PRIMARY KEY,
    project_id VARCHAR(36) NOT NULL,
    persona_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(100) NOT NULL,
    memory_id VARCHAR(36) NOT NULL,
    revision BIGINT NOT NULL,
    kind VARCHAR(8) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    code VARCHAR(40) NOT NULL
);
