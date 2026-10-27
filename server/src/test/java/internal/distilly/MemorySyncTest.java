package internal.distilly;

import internal.distilly.modules.memory.service.MemoryService;
import internal.distilly.modules.memory.service.MemorySyncService;
import internal.distilly.infrastructure.memory.HindsightMemoryProvider;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:memory-sync;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","platform.demo=true","platform.worker-token=memory-sync-synthetic-worker-token-32-characters"})
class MemorySyncTest {
    @Autowired MemorySyncService sync;
    @Autowired MemoryService memories;
    @Autowired PlatformFixture platform;
    @Autowired PersonaFixture personas;
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager transactions;
    String project,person,conversation,source,memory;
    @BeforeEach void setup(){
        project=platform.createProject("owner","合成持久同步").get("id").toString();
        platform.member("owner",project,"viewer","VIEWER");
        person=personas.create("owner",project,Map.of("name","合成同步人物","family","relationship","origin","original")).get("id").toString();
        String version=UUID.randomUUID().toString();conversation=UUID.randomUUID().toString();source=UUID.randomUUID().toString();memory=UUID.randomUUID().toString();
        db.update("INSERT INTO persona_version(id,persona_id,status,content,creator,created_at) VALUES (?,?,'APPROVED','{}','owner',1)",version,person);
        db.update("INSERT INTO persona_conversation(id,persona_id,version_id,user_id,title,emotion,created_at) VALUES (?,?,?,'viewer','合成来源','{}',1)",conversation,person,version);
        db.update("INSERT INTO persona_message(id,conversation_id,role,body,created_at) VALUES (?,?,'user','合成偏好：周五海边拍照',1)",source,conversation);
        db.update("INSERT INTO persona_memory VALUES (?,?,'viewer','合成偏好：周五海边拍照',?,'PENDING',1)",memory,person,source);
    }
    void confirm(String body){memories.memory("viewer",person,memory,Map.of("status","CONFIRMED","body",body));}
    Map<String,Object> latest(){return sync.inspect("viewer",person,memory).get(0);}
    long revision(Map<String,Object> row){return ((Number)row.get("revision")).longValue();}
    void status(int code,Runnable call){assertEquals(code,assertThrows(ResponseStatusException.class,call::run).getStatusCode().value());}

    @Test void confirmedIntentAndRepeatedSaveReuseOnePersistentOperation(){
        confirm("合成偏好：周五海边拍照");var first=latest();
        confirm("合成偏好：周五海边拍照");assertEquals(first,latest());
        assertEquals(1,sync.inspect("viewer",person,memory).size());
        var intent=sync.stageFixture("viewer",person,memory);
        var command=HindsightMemoryProvider.retain(intent.scope(),intent.document(),intent.operationId());
        assertEquals(first.get("operation_id"),command.arguments().get("operation_id"));
        assertEquals(1,intent.revision());assertEquals("RETAIN",intent.kind());
    }
    @Test void correctionAndAbaHaveDifferentMonotonicOperationsAndNoObsoleteBodies(){
        confirm("合成A");var first=latest();confirm("合成B");confirm("合成A");
        var rows=sync.inspect("viewer",person,memory);assertEquals(3,rows.size());assertEquals(3,revision(rows.get(0)));
        assertNotEquals(first.get("operation_id"),rows.get(0).get("operation_id"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM memory_sync_intent WHERE memory_id=? AND content IS NOT NULL",Integer.class,memory));
        assertEquals("SUPERSEDED",rows.get(1).get("state"));assertEquals("SUPERSEDED",rows.get(2).get("state"));
    }
    @Test void unknownOldOperationBlocksDeleteUntilKnownTerminalAndLateAckDoesNotRevive(){
        confirm("合成A");var old=sync.stageFixture("viewer",person,memory);sync.unknownFixture("viewer",person,old.operationId());
        memories.forget("viewer",person,memory);var deletion=latest();assertEquals("DELETE",deletion.get("kind"));
        status(409,()->sync.stageFixture("viewer",person,memory));
        status(422,()->sync.terminalFixture("viewer",person,old.operationId(),old.revision(),false));
        var acknowledged=sync.terminalFixture("viewer",person,old.operationId(),old.revision(),true);assertEquals(false,acknowledged.get("current"));
        assertEquals(deletion.get("operation_id"),latest().get("operation_id"));
        var remove=sync.stageFixture("viewer",person,memory);assertEquals("DELETE",remove.kind());assertNull(remove.document());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM persona_memory WHERE id=?",Integer.class,memory));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM memory_sync_intent WHERE memory_id=? AND content IS NOT NULL",Integer.class,memory));
    }
    @Test void correctionWhileOldRetainIsDispatchedCannotBeReordered(){
        confirm("合成A");var old=sync.stageFixture("viewer",person,memory);confirm("合成B");
        status(409,()->sync.stageFixture("viewer",person,memory));
        sync.terminalFixture("viewer",person,old.operationId(),old.revision(),true);
        var next=sync.stageFixture("viewer",person,memory);assertEquals("合成B",next.document().body());assertEquals(2,next.revision());
    }
    @Test void rejectedAndSourceDeletedMemoriesKeepOnlyCleanupTombstone(){
        confirm("合成A");memories.memory("viewer",person,memory,Map.of("status","REJECTED"));
        assertEquals("DELETE",latest().get("kind"));
        personas.deleteConversation("viewer",conversation);
        assertEquals("DELETE",latest().get("kind"));assertEquals(2,revision(latest()));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM memory_sync_intent WHERE memory_id=? AND content IS NOT NULL",Integer.class,memory));
    }
    @Test void transactionRollbackPreservesMemoryAndJournalTogether(){
        confirm("合成A");var before=latest();
        new TransactionTemplate(transactions).executeWithoutResult(tx->{confirm("合成B");memories.forget("viewer",person,memory);tx.setRollbackOnly();});
        assertEquals(before,latest());assertEquals("合成A",db.queryForObject("SELECT body FROM persona_memory WHERE id=?",String.class,memory));
    }
    @Test void otherUserOrPersonaAndRevokedMemberCannotInspectStageOrAcknowledge(){
        confirm("合成A");var intent=sync.stageFixture("viewer",person,memory);
        status(404,()->sync.inspect("owner",person,memory));status(404,()->sync.stageFixture("owner",person,memory));
        status(404,()->sync.terminalFixture("owner",person,intent.operationId(),intent.revision(),true));
        status(403,()->sync.track("owner",person,memory));
        String other=personas.create("owner",project,Map.of("name","其他合成人物","family","relationship","origin","original")).get("id").toString();
        status(404,()->sync.inspect("viewer",other,memory));
        db.update("DELETE FROM member WHERE project_id=? AND user_id='viewer'",project);
        status(403,()->sync.inspect("viewer",person,memory));status(403,()->sync.stageFixture("viewer",person,memory));
    }
    @Test void invalidOrRepeatedTerminalCallbacksNeverAdvanceAnotherRevision(){
        confirm("合成A");var intent=sync.stageFixture("viewer",person,memory);
        status(409,()->sync.terminalFixture("viewer",person,intent.operationId(),intent.revision()+1,true));
        var first=sync.terminalFixture("viewer",person,intent.operationId(),intent.revision(),true);
        assertEquals(first,sync.terminalFixture("viewer",person,intent.operationId(),intent.revision(),true));
        status(409,()->sync.stageFixture("viewer",person,memory));confirm("合成B");
        assertEquals(false,sync.terminalFixture("viewer",person,intent.operationId(),intent.revision(),true).get("current"));assertEquals(2,revision(latest()));
    }
    @Test void pendingCandidateIsNotExportedAndCorruptSourceCannotBecomeRetain(){
        status(404,()->sync.inspect("viewer",person,memory));
        db.update("UPDATE persona_conversation SET user_id='owner' WHERE id=?",conversation);confirm("合成A");
        assertEquals("DELETE",latest().get("kind"));assertNull(sync.stageFixture("viewer",person,memory).document());
    }
    @Test void stageRejectsDirectSqlChangesOutsideTrackedMutations(){
        confirm("合成A");db.update("UPDATE persona_memory SET body='合成未追踪变更' WHERE id=?",memory);
        status(409,()->sync.stageFixture("viewer",person,memory));assertEquals("PENDING",latest().get("state"));
    }
    @Test void concurrentStagingReturnsOnlyOneOfflineDispatch() throws Exception {
        confirm("合成A");var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            var a=pool.submit(()->{try{sync.stageFixture("viewer",person,memory);return 1;}catch(ResponseStatusException e){assertEquals(409,e.getStatusCode().value());return 0;}});
            var b=pool.submit(()->{try{sync.stageFixture("viewer",person,memory);return 1;}catch(ResponseStatusException e){assertEquals(409,e.getStatusCode().value());return 0;}});
            assertEquals(1,a.get(10,java.util.concurrent.TimeUnit.SECONDS)+b.get(10,java.util.concurrent.TimeUnit.SECONDS));
        }finally{pool.shutdownNow();}
    }
    @Test void failedPermissionOrStaleCorrectionCreatesNoIntent(){
        status(403,()->memories.memory("owner",person,memory,Map.of("status","CONFIRMED")));
        status(409,()->memories.memory("viewer",person,memory,Map.of("status","CONFIRMED","expected_revision","stale")));
        status(404,()->sync.inspect("viewer",person,memory));
    }
}
