package internal.distilly.modules.task.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WorkTaskRepository {

    private final JdbcTemplate db;

    public WorkTaskRepository(JdbcTemplate db) {
        this.db = db;
    }

    public List<Map<String, Object>> findVisibleTasks(String r, Object... parameters) {
        return db
            .queryForList("SELECT j.id FROM job j WHERE j.project_id=? AND j.id NOT IN (SELECT job_id FROM job_archive)"
                    + (r.equals("VIEWER") ? " AND j.kind='ANSWER' AND j.creator=?" : "")
                    + " ORDER BY j.created_at DESC LIMIT 200", parameters);
    }

    public String versionStatus(String versionId) {
        return db.query("SELECT status FROM profile_version WHERE id=?", rs -> rs.next() ? rs.getString(1) : null, versionId);
    }

    public String findTopicTitle(Object id) {
        return db.queryForObject("SELECT title FROM profile WHERE id=?", String.class, id);
    }

    public int advanceProgress(int completed, String jobId, int completedBound3) {
        return db.update("UPDATE job_progress SET completed=? WHERE job_id=? AND completed<=?", completed, jobId,
                completedBound3);
    }

    public List<Map<String, Object>> lockTopic(String id) {
        return db.queryForList("SELECT id FROM profile WHERE id=? FOR UPDATE", id);
    }

    public int insertQueuedTask(String id, String projectId, String profileId, String kind, Object payload,
            String creator, long createdAt) {
        return db.update(
                "INSERT INTO job(id,project_id,profile_id,kind,status,payload,creator,created_at) VALUES (?,?,?,?,'QUEUED',?,?,?)",
                id, projectId, profileId, kind, payload, creator, createdAt);
    }

    public int insertProgress(String jobId, Object total) {
        return db.update("INSERT INTO job_progress VALUES (?,0,?)", jobId, total);
    }

    public int countArchivedTask(String jobId) {
        return db.queryForObject("SELECT COUNT(*) FROM job_archive WHERE job_id=?", Integer.class, jobId);
    }

    public List<Map<String, Object>> findProgress(String jobId) {
        return db.queryForList("SELECT completed,total FROM job_progress WHERE job_id=?", jobId);
    }

    public List<Map<String, Object>> lockTask(String id) {
        return db.queryForList("SELECT id FROM job WHERE id=? FOR UPDATE", id);
    }

    public int changeState(Object state, Object resetState, String jobId) {
        return db.update(
                "UPDATE job SET status=?,lease_token=NULL,lease_until=NULL,attempt=CASE WHEN ?='QUEUED' THEN 0 ELSE attempt END,error_code=NULL WHERE id=?",
                state, resetState, jobId);
    }

    public int resetProgress(Object jobId) {
        return db.update("UPDATE job_progress SET completed=0 WHERE job_id=?", jobId);
    }

    public String executionFingerprint(String jobId) {
        return db.query("SELECT fingerprint FROM job_distill_execution WHERE job_id=?",
                rs -> rs.next() ? rs.getString(1) : null, jobId);
    }

    public void bindExecution(String jobId, String fingerprint, long createdAt) {
        db.update("INSERT INTO job_distill_execution VALUES (?,?,?)", jobId, fingerprint, createdAt);
    }

    public List<Map<String, Object>> chunkResults(String jobId) {
        return db.queryForList("SELECT chunk_id,input_digest,content FROM job_chunk_result WHERE job_id=?", jobId);
    }

    public int countChunkResults(String jobId) {
        return db.queryForObject("SELECT COUNT(*) FROM job_chunk_result WHERE job_id=?", Integer.class, jobId);
    }

    public void insertChunkResult(String jobId, String chunkId, String digest, String content, long completedAt) {
        db.update("INSERT INTO job_chunk_result VALUES (?,?,?,?,?)", jobId, chunkId, digest, content, completedAt);
    }

    public int syncCheckpointProgress(String jobId) {
        return db.update("UPDATE job_progress SET completed=(SELECT COUNT(*) FROM job_chunk_result WHERE job_id=?) WHERE job_id=?",
                jobId, jobId);
    }

    public int failExecutionChanged(String jobId, String token, long time) {
        return db.update("UPDATE job SET status='FAILED',error_code='EXECUTION_CHANGED' WHERE id=? AND status='RUNNING' AND lease_token=? AND lease_until>=?",
                jobId, token, time);
    }

    public int archiveTask(String jobId, String actor, long deletedAt) {
        return db.update("INSERT INTO job_archive VALUES (?,?,?)", jobId, actor, deletedAt);
    }

    public int restoreTask(String jobId) {
        return db.update("DELETE FROM job_archive WHERE job_id=?", jobId);
    }

    public List<Map<String, Object>> findArchivedTasks(String r, Object... parameters) {
        return db.queryForList("SELECT j.id FROM job j JOIN job_archive a ON a.job_id=j.id WHERE j.project_id=?"
                + (r.equals("OWNER") ? "" : " AND j.creator=?" + (r.equals("VIEWER") ? " AND j.kind='ANSWER'" : ""))
                + " ORDER BY a.deleted_at DESC LIMIT 200", parameters);
    }

    public int requeueFailedTask(String id) {
        return db.update("UPDATE job SET status='QUEUED',attempt=0,error_code=NULL WHERE id=? AND status='FAILED'", id);
    }

    public int failExhaustedLeases(long leaseUntil, int maxAttempts) {
        return db.update(
                "UPDATE job SET status='FAILED',error_code='LEASE_EXHAUSTED' WHERE status='RUNNING' AND lease_until<? AND attempt>=?",
                leaseUntil, maxAttempts);
    }

    public List<Map<String, Object>> findClaimableTasks(long leaseUntil, int maxAttempts) {
        return db.queryForList(
                "SELECT * FROM job WHERE status='QUEUED' OR (status='RUNNING' AND lease_until<? AND attempt<?) ORDER BY created_at LIMIT 10",
                leaseUntil, maxAttempts);
    }

    public int acquireLease(String leaseToken, long leaseUntil, Object id, long now, int maxAttempts) {
        return db.update(
                "UPDATE job SET status='RUNNING',lease_token=?,lease_until=?,attempt=attempt+1 WHERE id=? AND (status='QUEUED' OR (status='RUNNING' AND lease_until<? AND attempt<?))",
                leaseToken, leaseUntil, id, now, maxAttempts);
    }

    public int releaseLease(String id, String token, long now) {
        return db.update("UPDATE job SET status='QUEUED',attempt=CASE WHEN attempt>0 THEN attempt-1 ELSE 0 END,lease_until=NULL WHERE id=? AND status='RUNNING' AND lease_token=? AND lease_until>=?", id, token, now);
    }

    public int renewLease(long leaseUntil, String id, String leaseToken, long now) {
        return db.update(
                "UPDATE job SET lease_until=? WHERE id=? AND status='RUNNING' AND lease_token=? AND lease_until>=?",
                leaseUntil, id, leaseToken, now);
    }

    public int finishWithLease(String id, String leaseToken, long leaseUntil) {
        return db.update(
                "UPDATE job SET status='SUCCEEDED' WHERE id=? AND status='RUNNING' AND lease_token=? AND lease_until>=?",
                id, leaseToken, leaseUntil);
    }

    public int insertCandidateVersion(String id, Object profileId, Object baseId, Object content, Object work,
            Object creator, long createdAt) {
        return db.update(
                "INSERT INTO profile_version(id,profile_id,base_id,status,content,work,creator,created_at) VALUES (?,?,?,'CANDIDATE',?,?,?,?)",
                id, profileId, baseId, content, work, creator, createdAt);
    }

    public int finishProgress(String jobId) {
        return db.update("UPDATE job_progress SET completed=total WHERE job_id=?", jobId);
    }

    public int saveResult(Object result, String id) {
        return db.update("UPDATE job SET result=? WHERE id=?", result, id);
    }

    public int failOrRequeue(Object maxAttempts, String errorCode, String jobId, String leaseToken, long now) {
        return db.update(
                "UPDATE job SET status=CASE WHEN attempt<? THEN 'QUEUED' ELSE 'FAILED' END,error_code=? WHERE id=? AND status='RUNNING' AND lease_token=? AND lease_until>=?",
                maxAttempts, errorCode, jobId, leaseToken, now);
    }

}
