package internal.distilly.modules.workspace.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WorkspaceListRepository {

    private final JdbcTemplate db;

    public WorkspaceListRepository(JdbcTemplate db) {
        this.db = db;
    }

    public record Rows(List<Map<String, Object>> items, long total) {
    }

    public Rows search(String actor, String p, String r, String type, int page, int size, String q, String status) {
        return search(actor, p, r, type, page, size, q, status, null);
    }

    public Rows search(String actor, String p, String r, String type, int page, int size, String q, String status, String topic) {
        boolean material = type.equals("materials"), archived = type.equals("archived-jobs");
        var args = new ArrayList<Object>();
        args.add(p);
        String from = material ? " FROM material j WHERE j.project_id=?"
                : " FROM job j JOIN profile f ON f.id=j.profile_id WHERE j.project_id=? AND j.id "
                        + (archived ? "IN" : "NOT IN") + " (SELECT job_id FROM job_archive)";
        if (!material && !r.equals("OWNER") && (archived || r.equals("VIEWER"))) {
            from += " AND j.creator=?";
            args.add(actor);
        }
        if (!material && r.equals("VIEWER"))
            from += " AND j.kind='ANSWER'";
        if (topic != null) {
            from += " AND j.profile_id=? AND j.kind='DISTILL'";
            args.add(topic);
        }
        if (!q.isBlank()) {
            String pattern = "%" + q.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_")
                    + "%";
            from += " AND (LOWER(" + (material ? "j.filename" : "f.title")
                    + ") LIKE ? ESCAPE '!' OR LOWER(j.creator) LIKE ? ESCAPE '!' OR LOWER(j.id) LIKE ? ESCAPE '!')";
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
        }
        if (!material && !status.isBlank()) {
            from += " AND j.status=?";
            args.add(status);
        }
        long total = db.queryForObject("SELECT COUNT(*)" + from, Long.class, args.toArray());
        args.add(size);
        args.add((page - 1) * size);
        var rows = db.queryForList("SELECT " + (material ? "j.id,j.filename,j.creator,j.created_at" : "j.id") + from
                + " ORDER BY j.created_at DESC,j.id DESC LIMIT ? OFFSET ?", args.toArray());
        return new Rows(rows, total);
    }

    public String profileTitle(Object id) {
        return db.queryForObject("SELECT title FROM profile WHERE id=?", String.class, id);
    }

}
