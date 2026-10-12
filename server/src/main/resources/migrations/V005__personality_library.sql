ALTER TABLE persona ADD COLUMN library BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE persona_version ADD COLUMN source_personality_id VARCHAR(36);
ALTER TABLE persona_version ADD COLUMN source_personality_version_id VARCHAR(36);
