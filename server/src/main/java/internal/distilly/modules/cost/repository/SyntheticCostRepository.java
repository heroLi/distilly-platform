package internal.distilly.modules.cost.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import static internal.distilly.common.validation.InputChecks.error;

@Repository
public class SyntheticCostRepository {
    private final JdbcTemplate db;
    public SyntheticCostRepository(JdbcTemplate db) { this.db = db; }

    public void lockOwnerAndProject(String actor, String project) {
        var roles = db.queryForList("SELECT role FROM member WHERE user_id=? AND project_id=? FOR UPDATE", String.class, actor, project);
        if (roles.size() != 1 || !"OWNER".equals(roles.get(0))) throw error(403, "owner_required");
        // Every ledger writer takes this existing row lock, including first creation.
        if (db.queryForList("SELECT id FROM project WHERE id=? FOR UPDATE", project).isEmpty())
            throw error(404, "not_found");
    }
    public Map<String, Object> totals(String project) {
        return db.queryForMap("SELECT COALESCE(SUM(CASE WHEN status IN ('RESERVED','UNCERTAIN') THEN reserved_fen ELSE 0 END),0) AS held_fen, COALESCE(SUM(CASE WHEN status='SETTLED' THEN actual_fen ELSE 0 END),0) AS spent_fen, COUNT(*) AS reservation_count, COALESCE(SUM(CASE WHEN status IN ('RESERVED','UNCERTAIN') THEN 1 ELSE 0 END),0) AS unresolved_count FROM synthetic_cost_reservation WHERE project_id=?", project);
    }
    private String filter(String state) {
        return switch (state) {
            case "ALL" -> "";
            case "UNRESOLVED" -> " AND status IN ('RESERVED','UNCERTAIN')";
            default -> " AND status=?";
        };
    }
    private java.util.ArrayList<Object> parameters(String project, String state) {
        var args = new java.util.ArrayList<Object>();
        args.add(project);
        if (!state.equals("ALL") && !state.equals("UNRESOLVED")) args.add(state);
        return args;
    }
    public long count(String project, String state) {
        return db.queryForObject("SELECT COUNT(*) FROM synthetic_cost_reservation WHERE project_id=?" + filter(state),
                Long.class, parameters(project, state).toArray());
    }
    public List<Map<String, Object>> page(String project, String state, int page, int size) {
        var args = parameters(project, state);
        args.add(size);
        args.add((page - 1) * size);
        return db.queryForList("SELECT * FROM synthetic_cost_reservation WHERE project_id=?" + filter(state)
                + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?", args.toArray());
    }
    public List<Map<String, Object>> byRequest(String project, String key) {
        return db.queryForList("SELECT * FROM synthetic_cost_reservation WHERE project_id=? AND request_key=?", project, key);
    }
    public Map<String, Object> reservation(String project, String id) {
        var rows = db.queryForList("SELECT * FROM synthetic_cost_reservation WHERE project_id=? AND id=?", project, id);
        if (rows.isEmpty()) throw error(404, "not_found");
        return rows.get(0);
    }
    public List<Map<String, Object>> callback(String project, String key) {
        return db.queryForList("SELECT * FROM synthetic_cost_event WHERE project_id=? AND callback_key=?", project, key);
    }
    public List<Map<String, Object>> events(String id) {
        return db.queryForList("SELECT * FROM synthetic_cost_event WHERE reservation_id=? ORDER BY event_number", id);
    }
    public void reserve(String id, String project, String key, int amount, long now) {
        db.update("INSERT INTO synthetic_cost_reservation (id,project_id,request_key,reserved_fen,actual_fen,status,created_at,updated_at) VALUES (?,?,?,?,NULL,'RESERVED',?,?)", id, project, key, amount, now, now);
    }
    public void transition(String id, String state, Integer actual, long now) {
        db.update("UPDATE synthetic_cost_reservation SET status=?,actual_fen=?,updated_at=? WHERE id=?", state, actual, now, id);
    }
    public void event(String id, String project, String reservation, String callback, String outcome, String evidence, Integer actual, long now) {
        int number = db.queryForObject("SELECT COUNT(*) FROM synthetic_cost_event WHERE reservation_id=?", Integer.class, reservation) + 1;
        db.update("INSERT INTO synthetic_cost_event (id,project_id,reservation_id,event_number,callback_key,outcome,evidence,actual_fen,created_at) VALUES (?,?,?,?,?,?,?,?,?)", id, project, reservation, number, callback, outcome, evidence, actual, now);
    }
}
