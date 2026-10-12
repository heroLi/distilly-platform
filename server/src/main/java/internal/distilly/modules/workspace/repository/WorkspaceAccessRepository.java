package internal.distilly.modules.workspace.repository;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WorkspaceAccessRepository {

    private final JdbcTemplate db;

    public WorkspaceAccessRepository(JdbcTemplate db) {
        this.db = db;
    }

    public List<String> findRole(String userId, String projectId) {
        return db.queryForList("SELECT role FROM member WHERE user_id=? AND project_id=?", String.class, userId,
                projectId);
    }

}
