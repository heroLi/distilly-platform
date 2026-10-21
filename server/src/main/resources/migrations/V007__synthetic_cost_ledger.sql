-- Development-only simulated CNY amounts. Never a provider invoice or payment authorization.
CREATE TABLE IF NOT EXISTS synthetic_cost_reservation (
    id VARCHAR(36) PRIMARY KEY,
    project_id VARCHAR(36) NOT NULL REFERENCES project(id),
    request_key VARCHAR(80) NOT NULL,
    reserved_fen INT NOT NULL CHECK (reserved_fen BETWEEN 1 AND 1000),
    actual_fen INT,
    status VARCHAR(12) NOT NULL CHECK (status IN ('RESERVED','UNCERTAIN','SETTLED','RELEASED')),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE(project_id,request_key),
    UNIQUE(project_id,id),
    CHECK ((status IN ('RESERVED','UNCERTAIN') AND actual_fen IS NULL)
        OR (status='SETTLED' AND actual_fen IS NOT NULL AND actual_fen BETWEEN 0 AND reserved_fen)
        OR (status='RELEASED' AND actual_fen IS NOT NULL AND actual_fen=0))
);
CREATE TABLE IF NOT EXISTS synthetic_cost_event (
    id VARCHAR(36) PRIMARY KEY,
    project_id VARCHAR(36) NOT NULL REFERENCES project(id),
    reservation_id VARCHAR(36) NOT NULL,
    event_number INT NOT NULL CHECK (event_number BETWEEN 1 AND 3),
    callback_key VARCHAR(80),
    outcome VARCHAR(12) NOT NULL,
    evidence VARCHAR(24) NOT NULL,
    actual_fen INT,
    created_at BIGINT NOT NULL,
    UNIQUE(project_id,callback_key),
    UNIQUE(reservation_id,event_number),
    FOREIGN KEY(project_id,reservation_id) REFERENCES synthetic_cost_reservation(project_id,id)
);
CREATE INDEX IF NOT EXISTS synthetic_cost_project_idx ON synthetic_cost_reservation(project_id,status);
CREATE INDEX IF NOT EXISTS synthetic_cost_event_idx ON synthetic_cost_event(reservation_id,created_at);
