package internal.distilly.modules.persona.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PersonaRepository {

    private final JdbcTemplate db;

    public PersonaRepository(JdbcTemplate db) {
        this.db = db;
    }

    public List<Map<String, Object>> findVirtualPeople(String role, String projectId) {
        return db.queryForList(
                "SELECT id,name,family,published_id,created_at,creator FROM persona WHERE project_id=? AND library=FALSE AND deleted=FALSE"
                        + (role.equals("VIEWER") ? " AND published_id IS NOT NULL" : "")
                        + " ORDER BY created_at DESC,id",
                projectId);
    }

    public int insertPersona(String id, String projectId, Object name, Object family, Object definition, String creator,
            long createdAt) {
        return db.update(
                "INSERT INTO persona(id,project_id,name,family,definition,creator,created_at) VALUES (?,?,?,?,?,?,?)",
                id, projectId, name, family, definition, creator, createdAt);
    }

    public List<Map<String, Object>> findBindings(Object projectId, String sourcePersonalityId) {
        return db.queryForList(
                "SELECT target.id,target.name,v.source_personality_version_id,v.created_at FROM persona target JOIN persona_version v ON target.published_id=v.id WHERE target.project_id=? AND target.library=FALSE AND target.deleted=FALSE AND v.source_personality_id=? ORDER BY target.name,target.id",
                projectId, sourcePersonalityId);
    }

    public List<Map<String, Object>> findVersions(String role, String personaId) {
        return db.queryForList(
                "SELECT * FROM persona_version WHERE persona_id=?"
                        + (role.equals("VIEWER") ? " AND status='APPROVED'" : "") + " ORDER BY created_at DESC,id",
                personaId);
    }

    public int updateDefinition(Object name, Object family, Object definition, String id) {
        return db.update("UPDATE persona SET name=?,family=?,definition=? WHERE id=?", name, family, definition, id);
    }

    public int countActiveTasks(String personaId) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM persona_job WHERE persona_id=? AND status IN ('QUEUED','RUNNING')", Integer.class,
                personaId);
    }

    public int archivePersona(String id) {
        return db.update("UPDATE persona SET deleted=TRUE WHERE id=?", id);
    }

    public List<Map<String, Object>> findArchivedPeople(String projectId) {
        return db.queryForList(
                "SELECT id,name,family FROM persona WHERE project_id=? AND deleted=TRUE ORDER BY created_at DESC,id",
                projectId);
    }

    public int restorePersona(String id) {
        return db.update("UPDATE persona SET deleted=FALSE WHERE id=?", id);
    }

    public int countActiveGenerations(String personaId) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM persona_job WHERE persona_id=? AND kind='PERSONA' AND status IN ('QUEUED','RUNNING')",
                Integer.class, personaId);
    }

    public int insertCandidateVersion(String id, String personaId, Object baseId, Object content, String creator,
            long createdAt) {
        return db.update(
                "INSERT INTO persona_version(id,persona_id,base_id,status,content,creator,created_at) VALUES (?,?,?,'CANDIDATE',?,?,?)",
                id, personaId, baseId, content, creator, createdAt);
    }

    public int reviewVersion(Object status, String id) {
        return db.update("UPDATE persona_version SET status=? WHERE id=?", status, id);
    }

    public int setPublishedVersion(String publishedId, String id) {
        return db.update("UPDATE persona SET published_id=? WHERE id=?", publishedId, id);
    }

}
