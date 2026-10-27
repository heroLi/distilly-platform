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
        db.update("INSERT INTO memory_sync_intent VALUES (?,?,?,?,?,?,?,'PENDING',?,?,?,?,NULL)",persona,actor,memory,revision,project,operation,kind,source,fingerprint,content,now);
    }
    public int stage(String operation) {
        return db.update("UPDATE memory_sync_intent SET state='DISPATCHED' WHERE operation_id=? AND state='PENDING'",operation);
    }
    public void unknown(String operation) {
        db.update("UPDATE memory_sync_intent SET state='UNKNOWN' WHERE operation_id=? AND state='DISPATCHED'",operation);
    }
    public void terminal(String operation,long now) {
        db.update("UPDATE memory_sync_intent SET state='ACKED',content=NULL,terminal_at=? WHERE operation_id=? AND state IN ('DISPATCHED','UNKNOWN')",now,operation);
    }
}
