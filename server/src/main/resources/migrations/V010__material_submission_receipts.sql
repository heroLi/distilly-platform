-- One accepted text import per actor/workspace operation; never dedupe by name.
CREATE TABLE material_submission (
    project_id VARCHAR(36) NOT NULL REFERENCES project(id),
    actor_id VARCHAR(100) NOT NULL REFERENCES app_user(id),
    request_id VARCHAR(36) NOT NULL,
    filename VARCHAR(180) NOT NULL,
    raw_digest VARCHAR(64) NOT NULL,
    material_id VARCHAR(36) NOT NULL REFERENCES material(id),
    created_at BIGINT NOT NULL,
    PRIMARY KEY (project_id,actor_id,request_id)
);
