package internal.distilly.modules.knowledge.repository;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import static internal.distilly.common.validation.InputChecks.error;

@Repository
public class RecordQuestionRepository {
    private final JdbcTemplate db;

    public RecordQuestionRepository(JdbcTemplate db) { this.db = db; }

    public void lockMembership(String actor, Object project) {
        if (db.queryForList("SELECT role FROM member WHERE user_id=? AND project_id=? FOR UPDATE", actor, project).isEmpty())
            throw error(403, "forbidden");
    }

    public List<String> selectedProfiles(String persona) {
        return db.queryForList("SELECT profile_id FROM persona_knowledge WHERE persona_id=? ORDER BY profile_id", String.class, persona);
    }
}
