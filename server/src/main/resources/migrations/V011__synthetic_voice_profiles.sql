-- Mock-only profiles. No recordings, biometric templates or real provider credentials.
CREATE TABLE voice_profile (
    id VARCHAR(36) PRIMARY KEY,
    persona_id VARCHAR(36) NOT NULL REFERENCES persona(id),
    user_id VARCHAR(100) NOT NULL REFERENCES app_user(id),
    name VARCHAR(100) NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    authorization_id VARCHAR(36) NOT NULL,
    authorization_basis VARCHAR(24) NOT NULL,
    purpose VARCHAR(24) NOT NULL,
    authorization_status VARCHAR(12) NOT NULL,
    granted_at BIGINT NOT NULL,
    closed_at BIGINT,
    UNIQUE(persona_id,user_id,request_id)
);
CREATE TABLE voice_profile_job (
    profile_id VARCHAR(36) NOT NULL REFERENCES voice_profile(id),
    job_id VARCHAR(36) NOT NULL REFERENCES persona_job(id),
    operation VARCHAR(16) NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    PRIMARY KEY(profile_id,operation,request_id),
    UNIQUE(job_id)
);
