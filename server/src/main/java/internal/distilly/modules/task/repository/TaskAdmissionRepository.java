package internal.distilly.modules.task.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TaskAdmissionRepository {
    private final JdbcTemplate db;

    public TaskAdmissionRepository(JdbcTemplate db) { this.db = db; }

    public long activeTasks(String creator) {
        return db.queryForObject("SELECT (SELECT COUNT(*) FROM job WHERE creator=? AND status IN ('QUEUED','RUNNING','PAUSED')) + (SELECT COUNT(*) FROM persona_job WHERE creator=? AND status IN ('QUEUED','RUNNING'))",
                Long.class, creator, creator);
    }
}
