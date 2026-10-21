-- Atomic message/task acceptance, scoped to the owning private conversation.
CREATE TABLE chat_submission (
    conversation_id VARCHAR(36) NOT NULL REFERENCES persona_conversation(id),
    request_id VARCHAR(36) NOT NULL,
    actor_id VARCHAR(100) NOT NULL REFERENCES app_user(id),
    input_digest VARCHAR(64) NOT NULL,
    message_id VARCHAR(36) NOT NULL REFERENCES persona_message(id),
    job_id VARCHAR(36) NOT NULL REFERENCES persona_job(id),
    created_at BIGINT NOT NULL,
    PRIMARY KEY (conversation_id,request_id)
);
