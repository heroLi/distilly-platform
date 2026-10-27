-- Local outbox only. No provider consumer is enabled by this migration.
-- No FK to live memory/source rows: deletion must retain a content-free cleanup intent.
CREATE TABLE memory_sync_intent (
    persona_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(100) NOT NULL,
    memory_id VARCHAR(36) NOT NULL,
    revision BIGINT NOT NULL,
    project_id VARCHAR(36) NOT NULL,
    operation_id VARCHAR(36) NOT NULL UNIQUE,
    kind VARCHAR(8) NOT NULL,
    state VARCHAR(12) NOT NULL,
    source_id VARCHAR(36),
    fingerprint VARCHAR(64),
    content VARCHAR(1000),
    created_at BIGINT NOT NULL,
    terminal_at BIGINT,
    PRIMARY KEY(persona_id,user_id,memory_id,revision)
);
