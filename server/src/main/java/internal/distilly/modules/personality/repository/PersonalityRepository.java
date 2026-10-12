package internal.distilly.modules.personality.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PersonalityRepository {

    private final JdbcTemplate db;

    public PersonalityRepository(JdbcTemplate db) {
        this.db = db;
    }

    public List<Map<String, Object>> findLibraries(String role, String projectId) {
        return db.queryForList(
                "SELECT id,name,family,published_id,created_at,creator FROM persona WHERE project_id=? AND library=TRUE AND deleted=FALSE"
                        + (role.equals("VIEWER") ? " AND published_id IS NOT NULL" : "")
                        + " ORDER BY created_at DESC,id",
                projectId);
    }

    public int markAsLibrary(Object id) {
        return db.update("UPDATE persona SET library=TRUE WHERE id=?", id);
    }

    public int countActiveGenerations(String personaId) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM persona_job WHERE persona_id=? AND kind='PERSONA' AND status IN ('QUEUED','RUNNING')",
                Integer.class, personaId);
    }

    public int insertBoundVersion(String id, String personaId, Object baseId, Object content, String creator,
            long createdAt, String sourcePersonalityId, String sourcePersonalityVersionId) {
        return db.update(
                "INSERT INTO persona_version(id,persona_id,base_id,status,content,creator,created_at,source_personality_id,source_personality_version_id) VALUES (?,?,?,'APPROVED',?,?,?,?,?)",
                id, personaId, baseId, content, creator, createdAt, sourcePersonalityId, sourcePersonalityVersionId);
    }

    public int publishBoundVersion(String publishedId, String id) {
        return db.update("UPDATE persona SET published_id=? WHERE id=?", publishedId, id);
    }

}
