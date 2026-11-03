package internal.distilly;

import internal.distilly.config.PlatformProperties;
import internal.distilly.modules.memory.domain.MemoryProvider.Scope;
import internal.distilly.modules.memory.domain.MemorySyncReceipt;
import internal.distilly.modules.memory.domain.MemorySyncReceipt.Outcome;
import internal.distilly.modules.memory.service.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:memory-sync-consumer;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","platform.demo=true","platform.worker-token=memory-sync-consumer-synthetic-token-32-characters"})
class MemorySyncConsumerTest {
    @Autowired MemorySyncService sync;
    @Autowired MemoryService memories;
    @Autowired MemorySyncTarget defaultTarget;
    @Autowired PlatformFixture platform;
    @Autowired PersonaFixture personas;
    @Autowired JdbcTemplate db;
    PlatformProperties properties;
    String project,person,memory;

    @BeforeEach void setup(){
        // Each consumer owns its configuration: no mutation of shared Spring properties.
        properties=new PlatformProperties();properties.setDemo(true);
        project=platform.createProject("owner","合成离线同步消费者").get("id").toString();
        platform.member("owner",project,"viewer","VIEWER");
        person=personas.create("owner",project,Map.of("name","合成消费者人物","family","relationship","origin","original")).get("id").toString();
        String version=UUID.randomUUID().toString(),conversation=UUID.randomUUID().toString(),source=UUID.randomUUID().toString();memory=UUID.randomUUID().toString();
        db.update("INSERT INTO persona_version(id,persona_id,status,content,creator,created_at) VALUES (?,?,'APPROVED','{}','owner',1)",version,person);
        db.update("INSERT INTO persona_conversation(id,persona_id,version_id,user_id,title,emotion,created_at) VALUES (?,?,?,'viewer','合成来源','{}',1)",conversation,person,version);
        db.update("INSERT INTO persona_message(id,conversation_id,role,body,created_at) VALUES (?,?,'user','合成来源正文',1)",source,conversation);
        db.update("INSERT INTO persona_memory VALUES (?,?,'viewer','合成A',?,'PENDING',1)",memory,person,source);
        confirm("合成A");
    }
    void confirm(String body){memories.memory("viewer",person,memory,Map.of("status","CONFIRMED","body",body));}
    Map<String,Object> latest(){return sync.inspect("viewer",person,memory).get(0);}
    long revision(Map<String,Object> row){return ((Number)row.get("revision")).longValue();}
    MemorySyncConsumer consumer(MemorySyncTarget target){return new MemorySyncConsumer(sync,target,properties);}
    MemorySyncConsumer enabled(MemorySyncTarget target){properties.setMemorySyncMode("offline");return consumer(target);}
    void rejected(Runnable call){
        int code=assertThrows(ResponseStatusException.class,call::run).getStatusCode().value();
        assertTrue(code>=400&&code<500,"expected a rejected request, got "+code);
    }
    static MemorySyncReceipt receipt(MemorySyncService.Intent i,Outcome outcome,String code){
        return new MemorySyncReceipt(1,"OFFLINE_SIMULATION",i.scope(),i.memoryId(),i.operationId(),i.revision(),i.kind(),outcome,code);
    }
    static MemorySyncReceipt applied(MemorySyncService.Intent i){return receipt(i,Outcome.APPLIED,"NONE");}
    static final class Probe implements MemorySyncTarget {
        final AtomicInteger applies=new AtomicInteger(),reconciles=new AtomicInteger();
        Function<MemorySyncService.Intent,MemorySyncReceipt> applying=MemorySyncConsumerTest::applied;
        Function<MemorySyncService.Intent,MemorySyncReceipt> reconciling=MemorySyncConsumerTest::applied;
        @Override public MemorySyncReceipt apply(MemorySyncService.Intent i){applies.incrementAndGet();return applying.apply(i);}
        @Override public MemorySyncReceipt reconcile(MemorySyncService.Intent i){reconciles.incrementAndGet();return reconciling.apply(i);}
    }

    @Test void defaultAndNonDemoConsumersRejectEveryManualWriteWithoutCallingTarget(){
        var target=new Probe();var consumer=consumer(target);var before=latest();
        assertEquals("DISABLED",consumer.status("viewer",person).get("mode"));
        assertEquals(false,consumer.status("viewer",person).get("real_provider_enabled"));
        rejected(()->consumer.advance("viewer",person,memory));rejected(()->consumer.retry("viewer",person,memory));
        properties.setMemorySyncMode("offline");properties.setDemo(false);
        rejected(()->consumer.advance("viewer",person,memory));rejected(()->consumer.retry("viewer",person,memory));
        assertEquals(before,latest());assertEquals(0,target.applies.get());assertEquals(0,target.reconciles.get());
    }

    @Test void offlineTargetAppliesRetainAndContentFreeDeleteWithoutChangingSqlAuthority(){
        var consumer=enabled(defaultTarget);
        assertEquals("OFFLINE_SIMULATION",consumer.advance("viewer",person,memory).get("mode"));
        assertEquals("ACKED",latest().get("state"));
        var firstProjection=db.queryForMap("SELECT * FROM memory_sync_mock_document WHERE persona_id=? AND user_id='viewer' AND memory_id=?",person,memory);
        assertEquals(person,firstProjection.get("persona_id"));assertEquals("viewer",firstProjection.get("user_id"));assertEquals(memory,firstProjection.get("memory_id"));
        assertEquals(revision(latest()),revision(firstProjection));
        assertEquals(db.queryForObject("SELECT fingerprint FROM memory_sync_intent WHERE operation_id=?",String.class,latest().get("operation_id")),firstProjection.get("fingerprint"));
        assertEquals("合成A",db.queryForObject("SELECT body FROM persona_memory WHERE id=?",String.class,memory));
        confirm("合成B");consumer.advance("viewer",person,memory);
        var correctedProjection=db.queryForMap("SELECT * FROM memory_sync_mock_document WHERE persona_id=? AND user_id='viewer' AND memory_id=?",person,memory);
        assertEquals(revision(firstProjection)+1,revision(correctedProjection));
        assertNotEquals(firstProjection.get("fingerprint"),correctedProjection.get("fingerprint"));
        assertEquals(db.queryForObject("SELECT fingerprint FROM memory_sync_intent WHERE operation_id=?",String.class,latest().get("operation_id")),correctedProjection.get("fingerprint"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM memory_sync_mock_document WHERE persona_id=? AND user_id='viewer' AND memory_id=?",Integer.class,person,memory));
        assertEquals("合成B",db.queryForObject("SELECT body FROM persona_memory WHERE id=?",String.class,memory));
        memories.forget("viewer",person,memory);consumer.advance("viewer",person,memory);
        assertEquals("DELETE",latest().get("kind"));assertEquals("ACKED",latest().get("state"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM memory_sync_mock_document WHERE persona_id=? AND user_id='viewer' AND memory_id=?",Integer.class,person,memory));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM persona_memory WHERE id=?",Integer.class,memory));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM memory_sync_intent WHERE memory_id=? AND content IS NOT NULL",Integer.class,memory));
    }

    @Test void committedApplyWithLostReplyIsReconciledWithoutApplyingAgain(){
        var target=new Probe();
        target.applying=i->{defaultTarget.apply(i);throw new MemorySyncTarget.Unknown();};
        target.reconciling=defaultTarget::reconcile;
        var consumer=enabled(target);var before=latest();
        consumer.advance("viewer",person,memory);assertEquals("UNKNOWN",latest().get("state"));
        rejected(()->consumer.retry("viewer",person,memory));
        consumer.advance("viewer",person,memory);
        assertEquals("ACKED",latest().get("state"));assertEquals(before.get("operation_id"),latest().get("operation_id"));
        assertEquals(1,target.applies.get());assertEquals(1,target.reconciles.get());
    }

    @Test void unknownAndTimeoutNeverBecomeRetryableFailureOrRepeatApply(){
        var target=new Probe();target.applying=i->{throw new MemorySyncTarget.Unknown();};
        target.reconciling=i->{throw new RuntimeException("synthetic timeout");};
        var consumer=enabled(target);consumer.advance("viewer",person,memory);var uncertain=latest();
        consumer.advance("viewer",person,memory);rejected(()->consumer.retry("viewer",person,memory));
        assertEquals("UNKNOWN",latest().get("state"));assertEquals(uncertain.get("operation_id"),latest().get("operation_id"));
        assertEquals(1,sync.inspect("viewer",person,memory).size());assertEquals(1,target.applies.get());assertEquals(1,target.reconciles.get());
    }

    @Test void crashAfterStageIsDurablyFencedAndLateApplyCannotUndoTheFence(){
        var staged=sync.stageFixture("viewer",person,memory);var target=new Probe();target.reconciling=defaultTarget::reconcile;
        enabled(target).advance("viewer",person,memory);
        assertEquals("FAILED",latest().get("state"));assertEquals(0,target.applies.get());assertEquals(1,target.reconciles.get());
        var fenced=defaultTarget.reconcile(staged);
        assertEquals(Outcome.NOT_APPLIED,fenced.outcome());assertEquals("MOCK_FENCED",fenced.code());
        assertEquals(fenced,defaultTarget.apply(staged));assertEquals(fenced,defaultTarget.reconcile(staged));
    }

    @Test void knownFailureNeedsExplicitRetryWithNewRevisionAndOperation(){
        var target=new Probe();var old=new MemorySyncService.Intent[1];
        target.applying=i->{old[0]=i;return receipt(i,Outcome.NOT_APPLIED,"MOCK_REJECTED");};
        var consumer=enabled(target);consumer.advance("viewer",person,memory);var failed=latest();
        assertEquals("FAILED",failed.get("state"));
        consumer.advance("viewer",person,memory);
        assertEquals(failed,latest());assertEquals("FAILED",latest().get("state"));assertEquals(1,target.applies.get());
        consumer.retry("viewer",person,memory);var retry=latest();
        assertEquals("PENDING",retry.get("state"));assertEquals(revision(failed)+1,revision(retry));
        assertNotEquals(failed.get("operation_id"),retry.get("operation_id"));assertEquals(failed.get("operation_id"),retry.get("retry_of"));
        assertEquals(false,sync.settleReceipt("viewer",person,receipt(old[0],Outcome.NOT_APPLIED,"MOCK_REJECTED")).get("current"));
        assertEquals(retry,latest());
        rejected(()->consumer.retry("viewer",person,memory));
        target.applying=MemorySyncConsumerTest::applied;consumer.advance("viewer",person,memory);
        assertEquals("ACKED",latest().get("state"));assertEquals(2,target.applies.get());
    }

    @Test void receiptForAnotherRealOperationCannotSettleItOrClaimTheRequestedOperation(){
        String otherMemory=UUID.randomUUID().toString();
        String source=db.queryForObject("SELECT source_id FROM persona_memory WHERE id=?",String.class,memory);
        db.update("INSERT INTO persona_memory VALUES (?,?,'viewer','合成另一条记忆',?,'PENDING',1)",otherMemory,person,source);
        memories.memory("viewer",person,otherMemory,Map.of("status","CONFIRMED"));
        var requested=sync.stageFixture("viewer",person,memory);var other=sync.stageFixture("viewer",person,otherMemory);
        var otherBefore=sync.inspect("viewer",person,otherMemory).get(0);
        var target=new Probe();target.reconciling=i->applied(other);
        enabled(target).advance("viewer",person,memory);
        assertEquals("UNKNOWN",latest().get("state"));assertEquals(requested.operationId().toString(),latest().get("operation_id"));
        assertEquals(otherBefore,sync.inspect("viewer",person,otherMemory).get(0));
        assertEquals("DISPATCHED",otherBefore.get("state"));assertEquals(0,target.applies.get());assertEquals(1,target.reconciles.get());
    }

    @Test void malformedOrMismatchedReceiptsNeverSettleAnyOperation(){
        var i=sync.stageFixture("viewer",person,memory);var before=latest();
        var wrong=List.of(
            new MemorySyncReceipt(2,"OFFLINE_SIMULATION",i.scope(),memory,i.operationId(),i.revision(),i.kind(),Outcome.APPLIED,"NONE"),
            new MemorySyncReceipt(1,"REAL",i.scope(),memory,i.operationId(),i.revision(),i.kind(),Outcome.APPLIED,"NONE"),
            new MemorySyncReceipt(1,"OFFLINE_SIMULATION",new Scope(project,person,"owner"),memory,i.operationId(),i.revision(),i.kind(),Outcome.APPLIED,"NONE"),
            new MemorySyncReceipt(1,"OFFLINE_SIMULATION",i.scope(),UUID.randomUUID().toString(),i.operationId(),i.revision(),i.kind(),Outcome.APPLIED,"NONE"),
            new MemorySyncReceipt(1,"OFFLINE_SIMULATION",i.scope(),memory,UUID.randomUUID(),i.revision(),i.kind(),Outcome.APPLIED,"NONE"),
            new MemorySyncReceipt(1,"OFFLINE_SIMULATION",i.scope(),memory,i.operationId(),i.revision()+1,i.kind(),Outcome.APPLIED,"NONE"),
            new MemorySyncReceipt(1,"OFFLINE_SIMULATION",i.scope(),memory,i.operationId(),i.revision(),"DELETE",Outcome.APPLIED,"NONE"),
            receipt(i,Outcome.APPLIED,"MOCK_REJECTED"),receipt(i,Outcome.NOT_APPLIED,null));
        for(var receipt:wrong){rejected(()->sync.settleReceipt("viewer",person,receipt));assertEquals(before,latest());}
    }

    @Test void duplicateReceiptIsIdempotentContradictionRejectedAndOldReceiptCannotClaimCorrection(){
        var old=sync.stageFixture("viewer",person,memory);var receipt=applied(old);
        var first=sync.settleReceipt("viewer",person,receipt);
        assertEquals(first,sync.settleReceipt("viewer",person,receipt));
        rejected(()->sync.settleReceipt("viewer",person,receipt(old,Outcome.NOT_APPLIED,"MOCK_REJECTED")));
        assertEquals(first,latest());confirm("合成B");var corrected=latest();
        assertEquals(false,sync.settleReceipt("viewer",person,receipt).get("current"));
        assertEquals(corrected,latest());assertEquals("PENDING",latest().get("state"));
        assertEquals("合成B",db.queryForObject("SELECT body FROM persona_memory WHERE id=?",String.class,memory));
    }

    @Test void privateStatusAndSettlementRespectUserPersonaAndMembership(){
        var consumer=enabled(new Probe());var staged=sync.stageFixture("viewer",person,memory);
        assertTrue(sync.privateStatus("owner",person).isEmpty());
        rejected(()->consumer.advance("owner",person,memory));rejected(()->consumer.retry("owner",person,memory));
        rejected(()->sync.settleReceipt("owner",person,applied(staged)));
        String other=personas.create("owner",project,Map.of("name","其他合成人物","family","relationship","origin","original")).get("id").toString();
        rejected(()->consumer.advance("viewer",other,memory));rejected(()->sync.settleReceipt("viewer",other,applied(staged)));
        var status=consumer.status("viewer",person);String rendered=status.toString();
        assertFalse(rendered.contains("合成A"));assertFalse(rendered.contains("合成来源正文"));
        db.update("DELETE FROM member WHERE project_id=? AND user_id='viewer'",project);
        rejected(()->consumer.status("viewer",person));rejected(()->consumer.advance("viewer",person,memory));
        rejected(()->sync.settleReceipt("viewer",person,applied(staged)));assertEquals("DISPATCHED",db.queryForObject("SELECT state FROM memory_sync_intent WHERE operation_id=?",String.class,staged.operationId().toString()));
    }

    @Test void concurrentConsumersApplyOneOperationAtMostOnce() throws Exception {
        var target=new Probe();var consumer=enabled(target);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        Callable<Void> advance=()->{start.await();try{consumer.advance("viewer",person,memory);}catch(ResponseStatusException rejected){assertTrue(rejected.getStatusCode().is4xxClientError());}return null;};
        try{
            var a=pool.submit(advance);var b=pool.submit(advance);start.countDown();
            a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
            assertEquals(1,target.applies.get());assertEquals("ACKED",latest().get("state"));assertEquals(1,sync.inspect("viewer",person,memory).size());
        }finally{pool.shutdownNow();}
    }
}
