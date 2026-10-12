package internal.distilly.modules.workspace.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuditRepository {

    private final JdbcTemplate db;

    public AuditRepository(JdbcTemplate db) {
        this.db = db;
    }

    public int insert(String id, String projectId, String actor, String action, String resource, long createdAt) {
        return db.update("INSERT INTO audit VALUES (?,?,?,?,?,?)", id, projectId, actor, action, resource, createdAt);
    }

    public List<Map<String, Object>> findRecentByProject(String projectId) {
        return db.queryForList(
                "SELECT actor,action,resource,created_at FROM audit WHERE project_id=? ORDER BY created_at DESC LIMIT 200",
                projectId);
    }

}
