package internal.distilly.modules.task.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PersonaTaskRepository {

    private final JdbcTemplate db;

    public PersonaTaskRepository(JdbcTemplate db) {
        this.db = db;
    }

    public int insertQueuedTask(String id, String personaId, String conversationId, String kind, Object payload,
            String creator, long createdAt) {
        return db.update(
                "INSERT INTO persona_job(id,persona_id,conversation_id,kind,status,payload,creator,created_at) VALUES (?,?,?,?,'QUEUED',?,?,?)",
                id, personaId, conversationId, kind, payload, creator, createdAt);
    }

    public List<Map<String, Object>> findNewerConversationTasks(Object conversationId, String id, Object createdAt) {
        return db.queryForList(
                "SELECT id FROM persona_job WHERE conversation_id=? AND id<>? AND (created_at>? OR status IN ('QUEUED','RUNNING'))",
                conversationId, id, createdAt);
    }

    public int requeueTask(String id) {
        return db.update("UPDATE persona_job SET status='QUEUED',attempt=0,error_code=NULL,partial_reply='' WHERE id=?",
                id);
    }

    public int cancelTask(String id) {
        return db.update(
                "UPDATE persona_job SET status='CANCELLED',payload='{}',result=NULL,lease_token=NULL,partial_reply='' WHERE id=? AND status IN ('QUEUED','RUNNING','FAILED')",
                id);
    }

    public List<Map<String, Object>> findVisibleTasks(boolean editor, String personaId, String creator) {
        return db.queryForList("SELECT id FROM persona_job WHERE persona_id=? AND (creator=?"
                + (editor ? " OR kind NOT IN ('CHAT','TTS','ASR')" : "") + ") ORDER BY created_at DESC,id LIMIT 100",
                personaId, creator);
    }

    public int failExhaustedLeases(long leaseUntil, int maxAttempts) {
        return db.update(
                "UPDATE persona_job SET status='FAILED',error_code='LEASE_EXHAUSTED' WHERE status='RUNNING' AND lease_until<? AND attempt>=?",
                leaseUntil, maxAttempts);
    }

    public List<Map<String, Object>> findClaimableTasks(long leaseUntil, int maxAttempts, long agingBefore) {
        return db.queryForList(
                "SELECT * FROM persona_job WHERE status='QUEUED' OR (status='RUNNING' AND lease_until<? AND attempt<?) ORDER BY CASE WHEN created_at<=? THEN 0 WHEN kind='CHAT' THEN 1 ELSE 2 END,created_at,id LIMIT 10",
                leaseUntil, maxAttempts, agingBefore);
    }

    public int acquireLease(String leaseToken, long leaseUntil, Object id, long now, int maxAttempts) {
        return db.update(
                "UPDATE persona_job SET status='RUNNING',lease_token=?,lease_until=?,attempt=attempt+1,partial_reply='' WHERE id=? AND (status='QUEUED' OR (status='RUNNING' AND lease_until<? AND attempt<?))",
                leaseToken, leaseUntil, id, now, maxAttempts);
    }

    public List<Map<String, Object>> findRecentMessages(Object conversationId, Object currentMessageId) {
        return db.queryForList(
                "SELECT role,body FROM persona_message WHERE conversation_id=? AND id<>? ORDER BY created_at DESC,id DESC LIMIT 24",
                conversationId, currentMessageId);
    }

    public long countOtherMessages(Object conversationId, Object currentMessageId) {
        return db.queryForObject("SELECT COUNT(*) FROM persona_message WHERE conversation_id=? AND id<>?",
                Long.class, conversationId, currentMessageId);
    }

    public int saveContextMetadata(Object payload, Object id, String token) {
        return db.update("UPDATE persona_job SET payload=? WHERE id=? AND status='RUNNING' AND lease_token=?", payload, id, token);
    }

    public int rejectOversizedContext(Object id, String token) {
        return db.update("UPDATE persona_job SET status='FAILED',error_code='CONTEXT_LIMIT',lease_token=NULL WHERE id=? AND status='RUNNING' AND lease_token=?", id, token);
    }

    public int saveKnowledgeSnapshot(Object knowledgeSnapshot, Object id, String leaseToken) {
        return db.update(
                "UPDATE persona_job SET knowledge_snapshot=? WHERE id=? AND status='RUNNING' AND lease_token=?",
                knowledgeSnapshot, id, leaseToken);
    }

    public int releaseLease(String id, String token, long now) {
        return db.update("UPDATE persona_job SET status='QUEUED',attempt=CASE WHEN attempt>0 THEN attempt-1 ELSE 0 END,lease_until=NULL,partial_reply='' WHERE id=? AND status='RUNNING' AND lease_token=? AND lease_until>=?", id, token, now);
    }

    public int renewLease(long leaseUntil, String id, String leaseToken, long now) {
        return db.update(
                "UPDATE persona_job SET lease_until=? WHERE id=? AND status='RUNNING' AND lease_token=? AND lease_until>=?",
                leaseUntil, id, leaseToken, now);
    }

    public int failOrRequeue(int maxAttempts, String errorCode, String retryCode, String jobId, String leaseToken,
            long now) {
        return db.update(
                "UPDATE persona_job SET status=CASE WHEN attempt<? AND ?<>'MEDIA_NOT_CONFIGURED' THEN 'QUEUED' ELSE 'FAILED' END,error_code=?,partial_reply='' WHERE id=? AND status='RUNNING' AND lease_token=? AND lease_until>=?",
                maxAttempts, errorCode, retryCode, jobId, leaseToken, now);
    }

    public int saveReplyDelta(String partialReply, String id, String leaseToken, long leaseUntil) {
        return db.update(
                "UPDATE persona_job SET partial_reply=? WHERE id=? AND kind='CHAT' AND status='RUNNING' AND lease_token=? AND lease_until>=?",
                partialReply, id, leaseToken, leaseUntil);
    }

    public int insertCandidateVersion(String id, Object personaId, Object baseId, Object content, Object creator,
            long createdAt) {
        return db.update(
                "INSERT INTO persona_version(id,persona_id,base_id,status,content,creator,created_at) VALUES (?,?,?,'CANDIDATE',?,?,?)",
                id, personaId, baseId, content, creator, createdAt);
    }

    public int insertAssistantMessage(String id, Object conversationId, String body, Object emotion, long createdAt) {
        return db.update(
                "INSERT INTO persona_message(id,conversation_id,role,body,emotion,created_at) VALUES (?,?, 'assistant',?,?,?)",
                id, conversationId, body, emotion, createdAt);
    }

    public int saveMessageCitations(Object citations, String id) {
        return db.update("UPDATE persona_message SET citations=? WHERE id=?", citations, id);
    }

    public int updateConversationEmotion(Object emotion, Object id) {
        return db.update("UPDATE persona_conversation SET emotion=? WHERE id=?", emotion, id);
    }

    public int countDuplicateMemory(Object personaId, Object userId, String body) {
        return db.queryForObject("SELECT COUNT(*) FROM persona_memory WHERE persona_id=? AND user_id=? AND body=?",
                Integer.class, personaId, userId, body);
    }

    public int insertPendingMemory(String id, Object personaId, Object userId, String body, Object sourceId,
            long createdAt) {
        return db.update("INSERT INTO persona_memory VALUES (?,?,?,?,?,'PENDING',?)", id, personaId, userId, body,
                sourceId, createdAt);
    }

    public int saveAvatar(String avatar, Object id) {
        return db.update("UPDATE persona SET avatar=? WHERE id=?", avatar, id);
    }

    public int completeTask(Object result, String id) {
        return db.update("UPDATE persona_job SET status='SUCCEEDED',result=?,payload='{}',partial_reply='' WHERE id=?",
                result, id);
    }

}
