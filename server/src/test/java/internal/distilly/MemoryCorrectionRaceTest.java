package internal.distilly;

import internal.distilly.common.persistence.ResourceRepository;
import internal.distilly.modules.memory.repository.MemoryRepository;
import internal.distilly.modules.memory.service.MemoryService;
import internal.distilly.modules.memory.service.MemorySyncService;
import internal.distilly.modules.persona.service.PersonaAccessService;
import internal.distilly.modules.workspace.service.WorkspaceAccessService;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemoryCorrectionRaceTest {
    @Test
    void statusOnlyUpdateKeepsCorrectionCommittedWhileWaitingForLock() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:memory-race-" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        var db = new JdbcTemplate(source);
        db.execute("CREATE TABLE persona(id VARCHAR PRIMARY KEY,project_id VARCHAR,deleted BOOLEAN)");
        db.execute("CREATE TABLE persona_memory(id VARCHAR PRIMARY KEY,persona_id VARCHAR,user_id VARCHAR,body VARCHAR,status VARCHAR)");
        db.execute("CREATE TABLE persona_job(persona_id VARCHAR,creator VARCHAR,kind VARCHAR,status VARCHAR,payload VARCHAR,result VARCHAR,partial_reply VARCHAR,lease_token VARCHAR,knowledge_snapshot VARCHAR DEFAULT '[]')");
        db.update("INSERT INTO persona VALUES ('persona','project',FALSE)");
        db.update("INSERT INTO persona_memory VALUES ('memory','persona','alice','旧正文','CONFIRMED')");
        var read = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var first = new AtomicBoolean(true);
        var resources = new ResourceRepository(db) {
            @Override
            public Map<String, Object> one(String table, String id) {
                var row = super.one(table, id);
                if (table.equals("persona_memory") && first.compareAndSet(true, false)) {
                    read.countDown();
                    try {
                        if (!resume.await(5, TimeUnit.SECONDS)) throw new AssertionError("race synchronization timed out");
                    }
                    catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(error);
                    }
                }
                return row;
            }
        };
        var workspace = mock(WorkspaceAccessService.class);
        when(workspace.role("alice", "project")).thenReturn("VIEWER");
        var access = new PersonaAccessService(resources, workspace);
        var service = new MemoryService(new MemoryRepository(db), resources, access, mock(MemorySyncService.class));
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var pending = executor.submit(() -> tx.executeWithoutResult(status ->
                    service.memory("alice", "persona", "memory", Map.of("status", "CONFIRMED"))));
            assertTrue(read.await(5, TimeUnit.SECONDS), "status-only request must capture its pre-lock snapshot");
            tx.executeWithoutResult(status -> service.memory("alice", "persona", "memory",
                    Map.of("status", "CONFIRMED", "body", "已纠正正文")));
            resume.countDown();
            pending.get(5, TimeUnit.SECONDS);
            assertEquals("已纠正正文", db.queryForObject("SELECT body FROM persona_memory WHERE id='memory'", String.class));
        }
        finally {
            resume.countDown();
            executor.shutdownNow();
            db.execute("DROP ALL OBJECTS");
        }
    }
}
