package internal.distilly.modules.cost.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import static internal.distilly.common.validation.InputChecks.error;

@Repository
public class TextExecutionBudgetRepository {
    private final JdbcTemplate db;
    public TextExecutionBudgetRepository(JdbcTemplate db) { this.db = db; }
    public long lockGlobal() {
        return db.queryForObject("SELECT limit_nano_cny FROM text_execution_budget_guard WHERE id='SYNTHETIC_TEXT_GLOBAL_V1' FOR UPDATE", Long.class);
    }
    public Map<String, Object> task(String queue, String id) {
        // Fixed queue allowlist is selected by the service, never arbitrary SQL identifiers.
        var rows = queue.equals("WORK")
                ? db.queryForList("SELECT id,project_id,kind,status,lease_token,lease_until FROM job WHERE id=? FOR UPDATE", id)
                : db.queryForList("SELECT id,persona_id,kind,status,lease_token,lease_until FROM persona_job WHERE id=? FOR UPDATE", id);
        if (rows.size() != 1) throw error(404, "task_not_found");
        // Do not lock persona after its job: existing task writers take persona -> job.
        // Workspace identity is immutable, so a plain parent lookup suffices here.
        if (queue.equals("PERSONA")) rows.get(0).put("project_id",
                db.queryForObject("SELECT project_id FROM persona WHERE id=?", String.class, rows.get(0).get("persona_id")));
        return rows.get(0);
    }
    public Map<String, Object> reservation(String id) {
        var rows = db.queryForList("SELECT * FROM text_execution_reservation WHERE id=?", id);
        if (rows.size() != 1) throw error(404, "reservation_not_found");
        return rows.get(0);
    }
    public List<Map<String, Object>> byRequest(String key) {
        return db.queryForList("SELECT * FROM text_execution_reservation WHERE request_key=?", key);
    }
    public long pending(String queue, String job) {
        return db.queryForObject("SELECT COUNT(*) FROM text_execution_reservation WHERE queue=? AND job_id=? AND status IN ('RESERVED','DISPATCHED','UNCERTAIN')", Long.class, queue, job);
    }
    public Map<String, Object> totals(String project) {
        String condition = project == null ? "" : " WHERE project_id=?";
        return db.queryForMap("SELECT COALESCE(SUM(CASE WHEN status IN ('RESERVED','DISPATCHED','UNCERTAIN') THEN reserved_nano_cny ELSE 0 END),0) AS held_nano_cny, COALESCE(SUM(CASE WHEN status='SETTLED' THEN actual_nano_cny ELSE 0 END),0) AS spent_nano_cny, COUNT(*) AS reservation_count, COALESCE(SUM(CASE WHEN status IN ('RESERVED','DISPATCHED','UNCERTAIN') THEN 1 ELSE 0 END),0) AS unresolved_count FROM text_execution_reservation" + condition,
                project == null ? new Object[] {} : new Object[] {project});
    }
    public List<Map<String, Object>> recent(String project) {
        return db.queryForList("SELECT * FROM text_execution_reservation" + (project == null ? "" : " WHERE project_id=?")
                + " ORDER BY created_at DESC,id DESC LIMIT 100", project == null ? new Object[] {} : new Object[] {project});
    }
    public void reserve(String id, String project, String queue, String job, String token, String stage,
            String digest, String request, String price, long now) {
        db.update("INSERT INTO text_execution_reservation (id,project_id,queue,job_id,lease_token,stage,unit_digest,request_key,model,price_review,reserved_nano_cny,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,'deepseek-flash',?,1000000000,'RESERVED',?,?)",
                id, project, queue, job, token, stage, digest, request, price, now, now);
    }
    public void transition(String id, String status, Long amount, String receipt, long now) {
        db.update("UPDATE text_execution_reservation SET status=?,actual_nano_cny=?,receipt_key=?,updated_at=? WHERE id=?", status, amount, receipt, now, id);
    }
    public List<Map<String, Object>> byReceipt(String key) {
        return db.queryForList("SELECT id FROM text_execution_reservation WHERE receipt_key=?", key);
    }
    public List<Map<String, Object>> events(String id) {
        return db.queryForList("SELECT event_number,outcome,evidence,actual_nano_cny,created_at FROM text_execution_event WHERE reservation_id=? ORDER BY event_number", id);
    }
    public void event(String id, String reservation, String status, String evidence, Long amount, long now) {
        int number = db.queryForObject("SELECT COUNT(*) FROM text_execution_event WHERE reservation_id=?", Integer.class, reservation) + 1;
        db.update("INSERT INTO text_execution_event VALUES (?,?,?,?,?,?,?)", id, reservation, number, status, evidence, amount, now);
    }
    public void owner(String actor, String project) {
        var rows = db.queryForList("SELECT role FROM member WHERE user_id=? AND project_id=? FOR UPDATE", String.class, actor, project);
        if (rows.size() != 1 || !rows.get(0).equals("OWNER")) throw error(403, "owner_required");
    }
}
