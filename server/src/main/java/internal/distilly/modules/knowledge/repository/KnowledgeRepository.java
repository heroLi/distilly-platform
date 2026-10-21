package internal.distilly.modules.knowledge.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class KnowledgeRepository {

    private final JdbcTemplate db;

    public KnowledgeRepository(JdbcTemplate db) {
        this.db = db;
    }

    public int insertTopic(String id, String projectId, Object title) {
        return db.update("INSERT INTO profile VALUES (?,?,?,NULL)", id, projectId, title);
    }

    public List<Map<String, Object>> findTopics(String r, String projectId) {
        return db
            .queryForList(
                    "SELECT * FROM profile WHERE project_id=? AND id NOT IN (SELECT profile_id FROM profile_archive)"
                            + (r.equals("VIEWER") ? " AND published_id IS NOT NULL" : "") + " ORDER BY title",
                    projectId);
    }

    public List<Map<String, Object>> findArchivedTopics(String projectId) {
        return db.queryForList(
                "SELECT p.*,a.deleted_at FROM profile p JOIN profile_archive a ON a.profile_id=p.id WHERE p.project_id=? ORDER BY a.deleted_at DESC",
                projectId);
    }

    public List<Map<String, Object>> lockTopic(String id) {
        return db.queryForList("SELECT id FROM profile WHERE id=? FOR UPDATE", id);
    }

    public int countActiveTasks(String profileId) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM job WHERE profile_id=? AND status IN ('QUEUED','RUNNING','PAUSED')",
                Integer.class, profileId);
    }

    public int archiveTopic(String profileId, long deletedAt) {
        return db.update("INSERT INTO profile_archive VALUES (?,?)", profileId, deletedAt);
    }

    public List<Map<String, Object>> findTopicProject(String id) {
        return db.queryForList("SELECT project_id FROM profile WHERE id=?", id);
    }

    public int restoreTopic(String profileId) {
        return db.update("DELETE FROM profile_archive WHERE profile_id=?", profileId);
    }

    public List<Map<String, Object>> findVersions(String r, String profileId) {
        return db.queryForList(
                "SELECT * FROM profile_version WHERE profile_id=? AND status<>'DELETED'"
                        + (r.equals("VIEWER") ? " AND status='APPROVED'" : "") + " ORDER BY created_at DESC",
                profileId);
    }

    public int publishFirstVersion(String publishedId, String id) {
        return db.update("UPDATE profile SET published_id=? WHERE id=? AND published_id IS NULL", publishedId, id);
    }

    public int replacePublishedVersion(String publishedId, String id, Object publishedIdBound3) {
        return db.update("UPDATE profile SET published_id=? WHERE id=? AND published_id=?", publishedId, id,
                publishedIdBound3);
    }

    public int reviewCandidate(Object status, String reviewer, Object reviewNote, String versionId) {
        return db.update(
                "UPDATE profile_version SET status=?,reviewer=?,review_note=? WHERE id=? AND status='CANDIDATE'",
                status, reviewer, reviewNote, versionId);
    }

    public int setPublishedVersion(String publishedId, String id) {
        return db.update("UPDATE profile SET published_id=? WHERE id=?", publishedId, id);
    }


    public List<Map<String, Object>> versionLineage(String topicId) {
        return db.queryForList("SELECT id,base_id FROM profile_version WHERE profile_id=?", topicId);
    }

    public List<Map<String, Object>> completedDistillationResults(String topicId) {
        return db.queryForList("SELECT id,result FROM job WHERE profile_id=? AND kind='DISTILL' AND status='SUCCEEDED'",
                topicId);
    }

    public void withdrawVersion(String topicId, String versionId) {
        db.update("UPDATE profile_version SET status='DELETED' WHERE id=? AND profile_id=?", versionId, topicId);
        db.update("UPDATE profile SET published_id=NULL WHERE id=? AND published_id=?", topicId, versionId);
    }

    public int cancelChatsForWithdrawnTopic(String topicId) {
        // Match allowlist revocation: clear in-flight context for every persona
        // using this topic, including chats still queued before context assembly.
        return db.update(
                "UPDATE persona_job SET status='CANCELLED',payload='{}',result=NULL,partial_reply='',lease_token=NULL,knowledge_snapshot='[]' WHERE kind='CHAT' AND status IN ('QUEUED','RUNNING') AND persona_id IN (SELECT persona_id FROM persona_knowledge WHERE profile_id=?)",
                topicId);
    }

    public List<Map<String, Object>> currentVersions(String project, String topic) {
        String sql = "SELECT v.*,f.title AS profile_title FROM profile f JOIN profile_version v ON v.id=f.published_id "
                + "WHERE f.project_id=? AND v.status='APPROVED' AND f.id NOT IN (SELECT profile_id FROM profile_archive)"
                + (topic.isBlank() ? "" : " AND f.id=?") + " ORDER BY v.created_at DESC,f.id";
        return db.queryForList(sql, topic.isBlank() ? new Object[] { project } : new Object[] { project, topic });
    }

    public void insertRevision(String id, String topic, String base, String content, String work, String actor,
            String note, long createdAt) {
        db.update("INSERT INTO profile_version(id,profile_id,base_id,status,content,work,creator,reviewer,review_note,created_at) "
                + "VALUES (?,?,?,'APPROVED',?,?,?,?,?,?)", id, topic, base, content, work, actor, actor, note, createdAt);
    }
}
