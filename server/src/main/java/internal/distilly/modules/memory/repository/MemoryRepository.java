package internal.distilly.modules.memory.repository;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MemoryRepository {

    private final JdbcTemplate db;

    public MemoryRepository(JdbcTemplate db) {
        this.db = db;
    }

    public List<Map<String, Object>> findByPersonaAndUser(String personaId, String userId) {
        return db.queryForList(
                "SELECT * FROM persona_memory WHERE persona_id=? AND user_id=? ORDER BY created_at DESC,id", personaId,
                userId);
    }

    public void scanConfirmedForRecall(String personaId, String userId, Consumer<Map<String, Object>> onRow) {
        db.query(connection -> {
            var statement = connection.prepareStatement(
                    "SELECT m.id,m.body,m.source_id,m.created_at FROM persona_memory m JOIN persona_message s ON s.id=m.source_id JOIN persona_conversation c ON c.id=s.conversation_id WHERE m.persona_id=? AND m.user_id=? AND m.status='CONFIRMED' AND c.persona_id=? AND c.user_id=? ORDER BY m.created_at DESC,m.id");
            statement.setFetchSize(256);
            statement.setString(1, personaId);
            statement.setString(2, userId);
            statement.setString(3, personaId);
            statement.setString(4, userId);
            return statement;
        }, (org.springframework.jdbc.core.RowCallbackHandler) row -> {
            String body = row.getString("body");
            if (body != null && !body.isBlank())
                onRow.accept(Map.of("id", row.getString("id"), "body", body, "source_id", row.getString("source_id"),
                        "created_at", row.getLong("created_at")));
        });
    }

    public int cancelPendingChatsForUser(String personaId, String creator) {
        return db.update(
                "UPDATE persona_job SET status='CANCELLED',payload='{}',result=NULL,partial_reply='',lease_token=NULL,knowledge_snapshot='[]' WHERE persona_id=? AND creator=? AND kind='CHAT' AND status IN ('QUEUED','RUNNING')",
                personaId, creator);
    }

    public int countConfirmedSource(String id, String personaId, String userId) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM persona_memory m JOIN persona_message s ON s.id=m.source_id JOIN persona_conversation c ON c.id=s.conversation_id WHERE m.id=? AND m.persona_id=? AND m.user_id=? AND m.status='CONFIRMED' AND c.persona_id=? AND c.user_id=?",
                Integer.class, id, personaId, userId, personaId, userId);
    }

    public int updateStatusAndBody(String status, Object body, String id) {
        return db.update("UPDATE persona_memory SET status=?,body=? WHERE id=?", status, body, id);
    }

    public int deleteById(String id) {
        return db.update("DELETE FROM persona_memory WHERE id=?", id);
    }

}
