package internal.distilly.modules.knowledge.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PersonaKnowledgeRepository {

    private final JdbcTemplate db;

    public PersonaKnowledgeRepository(JdbcTemplate db) {
        this.db = db;
    }

    public List<Map<String, Object>> findAvailableTopics(String role, String personaId, Object projectId) {
        return db.queryForList(
                "SELECT f.id,f.title,f.published_id,CASE WHEN k.persona_id IS NULL THEN FALSE ELSE TRUE END AS selected FROM profile f LEFT JOIN persona_knowledge k ON k.profile_id=f.id AND k.persona_id=? WHERE f.project_id=? AND f.id NOT IN (SELECT profile_id FROM profile_archive) "
                        + (role.equals("VIEWER") ? " AND f.published_id IS NOT NULL" : "") + " ORDER BY f.title,f.id",
                personaId, projectId);
    }

    public List<Map<String, Object>> lockPersona(String id) {
        return db.queryForList("SELECT id FROM persona WHERE id=? FOR UPDATE", id);
    }

    public int deleteSelections(String personaId) {
        return db.update("DELETE FROM persona_knowledge WHERE persona_id=?", personaId);
    }

    public int insertSelection(String personaId, String profileId) {
        return db.update("INSERT INTO persona_knowledge VALUES (?,?)", personaId, profileId);
    }

    public int cancelChatsWithRevokedKnowledge(String personaId) {
        return db.update(
                "UPDATE persona_job SET status='CANCELLED',payload='{}',result=NULL,partial_reply='',lease_token=NULL,knowledge_snapshot='[]' WHERE persona_id=? AND kind='CHAT' AND status IN ('QUEUED','RUNNING')",
                personaId);
    }

    public List<Map<String, Object>> findPublishedClaims(String personaId, Object projectId) {
        return db.queryForList(
                "SELECT f.id,f.title,v.id AS version_id,v.content FROM persona_knowledge k JOIN profile f ON f.id=k.profile_id JOIN profile_version v ON v.id=f.published_id AND v.profile_id=f.id AND v.status='APPROVED' WHERE k.persona_id=? AND f.project_id=? AND f.id NOT IN (SELECT profile_id FROM profile_archive) ORDER BY f.id",
                personaId, projectId);
    }

    public int countAccessibleSnapshot(Object id, String personaId, String profileId, Object projectId) {
        // Approved history is retained, but a pending reply cannot treat it as current knowledge.
        return db.queryForObject(
                "SELECT COUNT(*) FROM persona_knowledge k JOIN profile f ON f.id=k.profile_id JOIN profile_version v ON v.id=? AND v.id=f.published_id AND v.profile_id=f.id AND v.status='APPROVED' WHERE k.persona_id=? AND k.profile_id=? AND f.project_id=? AND f.id NOT IN (SELECT profile_id FROM profile_archive)",
                Integer.class, id, personaId, profileId, projectId);
    }

}
