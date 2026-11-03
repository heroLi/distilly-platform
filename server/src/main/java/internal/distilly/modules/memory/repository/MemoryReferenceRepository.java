package internal.distilly.modules.memory.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import static internal.distilly.common.validation.InputChecks.error;

@Repository
public class MemoryReferenceRepository {
    private final JdbcTemplate db;
    public MemoryReferenceRepository(JdbcTemplate db) { this.db = db; }

    public void lockMembership(String actor, Object project) {
        if (db.queryForList("SELECT role FROM member WHERE user_id=? AND project_id=? FOR UPDATE", actor, project).isEmpty())
            throw error(403, "project_access_denied");
    }

    public List<Map<String,Object>> current(String id, String persona, String actor) {
        return db.queryForList("SELECT m.id,m.source_id,m.body FROM persona_memory m JOIN persona_message s ON s.id=m.source_id JOIN persona_conversation c ON c.id=s.conversation_id WHERE m.id=? AND m.persona_id=? AND m.user_id=? AND m.status='CONFIRMED' AND c.persona_id=? AND c.user_id=? AND s.role='user'",
                id, persona, actor, persona, actor);
    }
}
