-- One persistent synthetic pool per database, never real-provider authorization.
CREATE TABLE text_execution_budget_guard (
    id VARCHAR(40) PRIMARY KEY,
    limit_nano_cny BIGINT NOT NULL CHECK (limit_nano_cny=10000000000)
);
INSERT INTO text_execution_budget_guard VALUES ('SYNTHETIC_TEXT_GLOBAL_V1',10000000000);
CREATE TABLE text_execution_reservation (
    id VARCHAR(36) PRIMARY KEY,
    project_id VARCHAR(36) NOT NULL REFERENCES project(id),
    queue VARCHAR(8) NOT NULL CHECK (queue IN ('WORK','PERSONA')),
    job_id VARCHAR(36) NOT NULL,
    lease_token VARCHAR(36) NOT NULL,
    stage VARCHAR(24) NOT NULL,
    unit_digest VARCHAR(64) NOT NULL,
    request_key VARCHAR(80) NOT NULL UNIQUE,
    model VARCHAR(40) NOT NULL CHECK (model='deepseek-flash'),
    price_review VARCHAR(80) NOT NULL,
    reserved_nano_cny BIGINT NOT NULL CHECK (reserved_nano_cny=1000000000),
    actual_nano_cny BIGINT,
    status VARCHAR(12) NOT NULL CHECK (status IN ('RESERVED','DISPATCHED','UNCERTAIN','SETTLED','RELEASED')),
    receipt_key VARCHAR(160) UNIQUE,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    CHECK ((status IN ('RESERVED','DISPATCHED','UNCERTAIN') AND actual_nano_cny IS NULL AND receipt_key IS NULL)
        OR (status='SETTLED' AND actual_nano_cny IS NOT NULL AND actual_nano_cny BETWEEN 0 AND reserved_nano_cny AND receipt_key IS NOT NULL)
        OR (status='RELEASED' AND actual_nano_cny IS NOT NULL AND actual_nano_cny=0 AND receipt_key IS NULL))
);
CREATE INDEX text_execution_task_idx ON text_execution_reservation(queue,job_id,status);
CREATE INDEX text_execution_project_idx ON text_execution_reservation(project_id,status);
CREATE TABLE text_execution_event (
    id VARCHAR(36) PRIMARY KEY,
    reservation_id VARCHAR(36) NOT NULL REFERENCES text_execution_reservation(id),
    event_number INT NOT NULL CHECK (event_number BETWEEN 1 AND 4),
    outcome VARCHAR(12) NOT NULL,
    evidence VARCHAR(40) NOT NULL,
    actual_nano_cny BIGINT,
    created_at BIGINT NOT NULL,
    UNIQUE(reservation_id,event_number)
);
