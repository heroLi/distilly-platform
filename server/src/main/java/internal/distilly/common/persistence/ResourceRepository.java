package internal.distilly.common.persistence;

import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import static internal.distilly.common.validation.InputChecks.error;

@Repository
public class ResourceRepository {

    private static final Set<String> TABLES = Set.of("app_user", "project", "material", "profile", "profile_version", "job",
            "persona", "persona_version", "persona_conversation", "persona_memory", "persona_message", "persona_job", "voice_profile");

    private final JdbcTemplate db;

    public ResourceRepository(JdbcTemplate db) {
        this.db = db;
    }

    public Map<String, Object> one(String table, String resource) {
        if (!TABLES.contains(table))
            throw new IllegalArgumentException("Unknown resource table");
        var rows = db.queryForList("SELECT * FROM " + table + " WHERE id=?", resource);
        if (rows.isEmpty() || (table.equals("profile") && db
            .queryForObject("SELECT COUNT(*) FROM profile_archive WHERE profile_id=?", Integer.class, resource) > 0))
            throw error(404, "not_found");
        return rows.get(0);
    }

    public String projectOf(String profile) {
        return (String) one("profile", profile).get("project_id");
    }

    public void lock(String table, String id) {
        if (!TABLES.contains(table))
            throw new IllegalArgumentException("Unknown resource table");
        db.queryForList("SELECT id FROM " + table + " WHERE id=? FOR UPDATE", id);
    }

}
