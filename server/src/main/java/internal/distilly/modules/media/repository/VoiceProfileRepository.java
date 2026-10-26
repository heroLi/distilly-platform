package internal.distilly.modules.media.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class VoiceProfileRepository {
    private final JdbcTemplate db;
    public VoiceProfileRepository(JdbcTemplate db) { this.db = db; }

    public void insert(String id, String persona, String actor, String name, String request, String authorization, long time) {
        db.update("INSERT INTO voice_profile VALUES (?,?,?,?,?,?,'SYNTHETIC_ONLY','MOCK_PREVIEW','ACTIVE',?,NULL)",
                id, persona, actor, name, request, authorization, time);
    }
    public List<Map<String, Object>> byRequest(String persona, String actor, String request) {
        return db.queryForList("SELECT * FROM voice_profile WHERE persona_id=? AND user_id=? AND request_id=?", persona, actor, request);
    }
    public List<Map<String, Object>> list(String persona, String actor) {
        return db.queryForList("SELECT * FROM voice_profile WHERE persona_id=? AND user_id=? AND authorization_status<>'DELETED' ORDER BY granted_at DESC,id LIMIT 50", persona, actor);
    }
    public List<Map<String, Object>> jobs(Object profile) {
        return db.queryForList("SELECT j.id,j.kind,j.status,j.error_code,j.result FROM voice_profile_job v JOIN persona_job j ON j.id=v.job_id WHERE v.profile_id=? ORDER BY j.created_at,j.id", profile);
    }
    public List<Map<String, Object>> jobByRequest(String profile, String operation, String request) {
        return db.queryForList("SELECT job_id FROM voice_profile_job WHERE profile_id=? AND operation=? AND request_id=?", profile, operation, request);
    }
    public void link(String profile, String job, String operation, String request) {
        db.update("INSERT INTO voice_profile_job VALUES (?,?,?,?)", profile, job, operation, request);
    }
    public List<Map<String, Object>> profileForJob(Object job) {
        return db.queryForList("SELECT p.* FROM voice_profile p JOIN voice_profile_job v ON v.profile_id=p.id WHERE v.job_id=?", job);
    }
    public void close(String profile, String status, long time) {
        db.update("UPDATE voice_profile SET authorization_status=?,closed_at=COALESCE(closed_at,?),name=CASE WHEN ?='DELETED' THEN '' ELSE name END WHERE id=?", status, time, status, profile);
        db.update("UPDATE persona_job SET status='CANCELLED',payload='{}',result=NULL,lease_token=NULL,lease_until=NULL,partial_reply='' WHERE id IN (SELECT job_id FROM voice_profile_job WHERE profile_id=?)", profile);
    }
    public void cancelJob(Object job) {
        db.update("UPDATE persona_job SET status='CANCELLED',payload='{}',result=NULL,lease_token=NULL,lease_until=NULL,partial_reply='' WHERE id=? AND kind IN ('VOICE_CLONE','VOICE_PREVIEW')", job);
    }
}
