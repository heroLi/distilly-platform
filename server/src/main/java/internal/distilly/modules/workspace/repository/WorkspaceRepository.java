package internal.distilly.modules.workspace.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WorkspaceRepository {

    private final JdbcTemplate db;

    public WorkspaceRepository(JdbcTemplate db) {
        this.db = db;
    }

    public List<Map<String, Object>> findAccessibleProjects(String userId) {
        return db.queryForList(
                "SELECT p.*,m.role FROM project p JOIN member m ON m.project_id=p.id WHERE m.user_id=? ORDER BY p.title",
                userId);
    }

    public int insertProject(String id, String title) {
        return db.update("INSERT INTO project VALUES (?,?)", id, title);
    }

    public int insertOwner(String projectId, String userId, String role) {
        return db.update("INSERT INTO member VALUES (?,?,?)", projectId, userId, role);
    }

    public List<Map<String, Object>> findMembers(String projectId) {
        return db.queryForList("SELECT user_id,role FROM member WHERE project_id=? ORDER BY user_id", projectId);
    }

    public int countUser(String id) {
        return db.queryForObject("SELECT COUNT(*) FROM app_user WHERE id=?", Integer.class, id);
    }

    public int updateMemberRole(String role, String projectId, String userId) {
        return db.update("UPDATE member SET role=? WHERE project_id=? AND user_id=?", role, projectId, userId);
    }

    public int insertMember(String projectId, String userId, String role) {
        return db.update("INSERT INTO member VALUES (?,?,?)", projectId, userId, role);
    }

}
