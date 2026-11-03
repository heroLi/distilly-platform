package internal.distilly.modules.memory.repository;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MemorySyncRepository {
    private final JdbcTemplate db;
    public MemorySyncRepository(JdbcTemplate db) { this.db=db; }
    public List<Map<String,Object>> history(String persona,String actor,String memory) {
        return db.queryForList("SELECT * FROM memory_sync_intent WHERE persona_id=? AND user_id=? AND memory_id=? ORDER BY revision DESC",persona,actor,memory);
    }
    public List<Map<String,Object>> operation(String persona,String actor,String operation) {
        return db.queryForList("SELECT * FROM memory_sync_intent WHERE persona_id=? AND user_id=? AND operation_id=?",persona,actor,operation);
    }
    public void append(String project,String persona,String actor,String memory,long revision,String operation,String kind,String source,String fingerprint,String content,long now) {
        // Never retain an obsolete local snapshot. Uncertain external operations remain unresolved blockers.
        db.update("UPDATE memory_sync_intent SET content=NULL,state=CASE WHEN state='PENDING' THEN 'SUPERSEDED' ELSE state END WHERE persona_id=? AND user_id=? AND memory_id=?",persona,actor,memory);
        db.update("INSERT INTO memory_sync_intent(persona_id,user_id,memory_id,revision,project_id,operation_id,kind,state,source_id,fingerprint,content,created_at,terminal_at) VALUES (?,?,?,?,?,?,?,'PENDING',?,?,?,?,NULL)",persona,actor,memory,revision,project,operation,kind,source,fingerprint,content,now);
    }
    public int stage(String operation,long now) {
        return db.update("UPDATE memory_sync_intent SET state='DISPATCHED',dispatched_at=? WHERE operation_id=? AND state='PENDING'",now,operation);
    }
    public void unknown(String operation) {
        db.update("UPDATE memory_sync_intent SET state='UNKNOWN',error_code='RESULT_UNCONFIRMED' WHERE operation_id=? AND state='DISPATCHED'",operation);
    }
    public void terminal(String operation,long now) {
        db.update("UPDATE memory_sync_intent SET state='ACKED',content=NULL,terminal_at=?,receipt_mode='FIXTURE_ONLY',receipt_outcome='APPLIED',error_code=NULL WHERE operation_id=? AND state IN ('DISPATCHED','UNKNOWN')",now,operation);
    }
    public void settle(String operation,String outcome,String code,long now) {
        db.update("UPDATE memory_sync_intent SET state=?,content=NULL,terminal_at=?,receipt_mode='OFFLINE_SIMULATION',receipt_outcome=?,error_code=? WHERE operation_id=? AND state IN ('DISPATCHED','UNKNOWN')",
                "APPLIED".equals(outcome)?"ACKED":"FAILED",now,outcome,code,operation);
    }
    public void retryOf(String operation,String previous) {db.update("UPDATE memory_sync_intent SET retry_of=? WHERE operation_id=?",previous,operation);}
    public List<Map<String,Object>> latestForUser(String persona,String actor) {
        return db.queryForList("SELECT i.memory_id,i.operation_id,i.revision,i.kind,i.state,i.created_at,i.dispatched_at,i.terminal_at,i.receipt_mode,i.receipt_outcome,i.error_code,i.retry_of,EXISTS(SELECT 1 FROM memory_sync_intent u WHERE u.persona_id=i.persona_id AND u.user_id=i.user_id AND u.memory_id=i.memory_id AND u.state IN ('DISPATCHED','UNKNOWN')) AS blocked_by_unresolved_operation FROM memory_sync_intent i WHERE i.persona_id=? AND i.user_id=? AND NOT EXISTS(SELECT 1 FROM memory_sync_intent n WHERE n.persona_id=i.persona_id AND n.user_id=i.user_id AND n.memory_id=i.memory_id AND n.revision>i.revision) ORDER BY i.created_at DESC,i.memory_id LIMIT 50",persona,actor);
    }
}
