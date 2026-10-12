CREATE TABLE persona_knowledge (persona_id VARCHAR(36) NOT NULL REFERENCES persona(id), profile_id VARCHAR(36) NOT NULL REFERENCES profile(id), PRIMARY KEY(persona_id,profile_id));
ALTER TABLE persona_job ADD COLUMN knowledge_snapshot TEXT NOT NULL DEFAULT '[]';
ALTER TABLE persona_message ADD COLUMN citations TEXT NOT NULL DEFAULT '[]';
