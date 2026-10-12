package internal.distilly.modules.material.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MaterialRepository {

    private final JdbcTemplate db;

    public MaterialRepository(JdbcTemplate db) {
        this.db = db;
    }

    public int insert(String id, String projectId, String filename, String body, String digest, String creator,
            long createdAt) {
        return db.update("INSERT INTO material VALUES (?,?,?,?,?,?,?)", id, projectId, filename, body, digest, creator,
                createdAt);
    }

    public List<Map<String, Object>> findMetadataByProject(String projectId) {
        return db.queryForList(
                "SELECT id,filename,digest,creator,created_at FROM material WHERE project_id=? ORDER BY created_at",
                projectId);
    }

}
