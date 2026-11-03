package internal.distilly.modules.memory.repository;

import internal.distilly.modules.memory.domain.MemorySyncReceipt;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OfflineMemorySyncRepository {
    private final JdbcTemplate db;
    public OfflineMemorySyncRepository(JdbcTemplate db) { this.db=db; }
    public List<Map<String,Object>> receipt(String operation) { return db.queryForList("SELECT * FROM memory_sync_mock_receipt WHERE operation_id=?",operation); }
    public void receipt(MemorySyncReceipt receipt) {
        var s=receipt.scope();
        db.update("INSERT INTO memory_sync_mock_receipt VALUES (?,?,?,?,?,?,?,?,?)",receipt.operationId().toString(),s.projectId(),s.personaId(),s.userId(),receipt.memoryId(),receipt.revision(),receipt.kind(),receipt.outcome().name(),receipt.code());
    }
    public void project(String persona,String actor,String memory,long revision,String fingerprint) {
        db.update("DELETE FROM memory_sync_mock_document WHERE persona_id=? AND user_id=? AND memory_id=?",persona,actor,memory);
        db.update("INSERT INTO memory_sync_mock_document VALUES (?,?,?,?,?)",persona,actor,memory,revision,fingerprint);
    }
    public void remove(String persona,String actor,String memory) {
        db.update("DELETE FROM memory_sync_mock_document WHERE persona_id=? AND user_id=? AND memory_id=?",persona,actor,memory);
    }
}
