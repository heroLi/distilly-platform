package internal.distilly.modules.conversation.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ConversationRepository {

    private final JdbcTemplate db;

    public ConversationRepository(JdbcTemplate db) {
        this.db = db;
    }

    public int insertConversation(String id, String personaId, String versionId, String userId, String title,
            Object emotion, long createdAt) {
        return db.update(
                "INSERT INTO persona_conversation(id,persona_id,version_id,user_id,title,emotion,created_at) VALUES (?,?,?,?,?,?,?)",
                id, personaId, versionId, userId, title, emotion, createdAt);
    }

    public List<Map<String, Object>> findByPersonaAndUser(String personaId, String userId) {
        return db.queryForList(
                "SELECT id,title,version_id,created_at FROM persona_conversation WHERE persona_id=? AND user_id=? ORDER BY created_at DESC,id",
                personaId, userId);
    }

    public List<Map<String, Object>> findMessages(String conversationId) {
        return db.queryForList(
                "SELECT id,role,body,emotion,created_at,image,citations FROM persona_message WHERE conversation_id=? ORDER BY created_at,id",
                conversationId);
    }

    public List<Map<String, Object>> findActiveTasks(String conversationId) {
        return db.queryForList(
                "SELECT id,status,error_code,partial_reply FROM persona_job WHERE conversation_id=? AND status IN ('QUEUED','RUNNING') ORDER BY created_at DESC",
                conversationId);
    }

    public List<Map<String, Object>> findLatestChatTask(String conversationId) {
        return db.queryForList(
                "SELECT id,status,error_code,attempt FROM persona_job WHERE conversation_id=? AND kind='CHAT' ORDER BY created_at DESC,id LIMIT 1",
                conversationId);
    }

    public int countActiveTasks(String conversationId) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM persona_job WHERE conversation_id=? AND status IN ('QUEUED','RUNNING')",
                Integer.class, conversationId);
    }

    public int insertUserMessage(String id, String conversationId, String body, long createdAt, String image) {
        return db.update(
                "INSERT INTO persona_message(id,conversation_id,role,body,created_at,image) VALUES (?,?,'user',?,?,?)",
                id, conversationId, body, createdAt, image);
    }

    public int nameFirstMessage(Object title, String id) {
        return db.update("UPDATE persona_conversation SET title=? WHERE id=? AND title='新的交流'", title, id);
    }

    public int updateMemoryEnabled(boolean memoryEnabled, String id) {
        return db.update("UPDATE persona_conversation SET memory_enabled=? WHERE id=?", memoryEnabled, id);
    }

    public int cancelActiveTasks(String conversationId) {
        return db.update(
                "UPDATE persona_job SET status='CANCELLED',payload='{}',result=NULL,partial_reply='',lease_token=NULL WHERE conversation_id=? AND status IN ('QUEUED','RUNNING')",
                conversationId);
    }

    public int clearTaskPayloads(String conversationId) {
        return db.update(
                "UPDATE persona_job SET status='CANCELLED',payload='{}',result=NULL,lease_token=NULL,partial_reply='' WHERE conversation_id=?",
                conversationId);
    }

    public int deleteDerivedMemories(String conversationId) {
        return db.update(
                "DELETE FROM persona_memory WHERE source_id IN (SELECT id FROM persona_message WHERE conversation_id=?)",
                conversationId);
    }

    public int deleteTasks(String conversationId) {
        return db.update("DELETE FROM persona_job WHERE conversation_id=?", conversationId);
    }

    public int deleteMessages(String conversationId) {
        return db.update("DELETE FROM persona_message WHERE conversation_id=?", conversationId);
    }

    public int deleteConversation(String id) {
        return db.update("DELETE FROM persona_conversation WHERE id=?", id);
    }

}
