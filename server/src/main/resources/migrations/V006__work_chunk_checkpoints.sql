CREATE TABLE job_distill_execution (
    job_id VARCHAR(36) PRIMARY KEY REFERENCES job(id) ON DELETE CASCADE,
    fingerprint VARCHAR(64) NOT NULL,
    created_at BIGINT NOT NULL
);
CREATE TABLE job_chunk_result (
    job_id VARCHAR(36) NOT NULL REFERENCES job(id) ON DELETE CASCADE,
    chunk_id VARCHAR(100) NOT NULL,
    input_digest VARCHAR(64) NOT NULL,
    content TEXT NOT NULL,
    completed_at BIGINT NOT NULL,
    PRIMARY KEY (job_id, chunk_id)
);
