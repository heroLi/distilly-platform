package internal.distilly;

import internal.distilly.modules.memory.service.MemoryService;
import internal.distilly.modules.memory.service.SqlMemoryProvider;
import internal.distilly.modules.task.service.PersonaTaskService;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import internal.distilly.modules.memory.repository.MemoryRepository;
import java.util.List;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:claim-recall-isolation;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "platform.demo=true", "platform.worker-token=claim-recall-synthetic-worker-token-32"})
class ClaimRecallIsolationTest {
    @Autowired PlatformFixture platform;
    @Autowired PersonaFixture personas;
    @Autowired PersonaTaskService tasks;
    @Autowired MemoryService memories;
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager manager;
    @MockitoSpyBean SqlMemoryProvider sql;
    @MockitoSpyBean MemoryRepository memoryRepository;
    String project,person,conversation,memory;

    @BeforeEach void seed() {
        db.update("DELETE FROM persona_job");
        project=platform.createProject("owner","合成领取隔离").get("id").toString();
        platform.member("owner",project,"viewer","VIEWER");
        person=personas.create("owner",project,Map.of("name","合成领取伙伴","family","relationship","origin","original")).get("id").toString();
        String version=UUID.randomUUID().toString();
        db.update("INSERT INTO persona_version(id,persona_id,status,content,creator,created_at) VALUES (?,?,'APPROVED','{}','owner',1)",version,person);
        db.update("UPDATE persona SET published_id=? WHERE id=?",version,person);
        conversation=personas.start("viewer",person,null).get("id").toString();
        String source=UUID.randomUUID().toString();memory=UUID.randomUUID().toString();
        db.update("INSERT INTO persona_message(id,conversation_id,role,body,created_at) VALUES (?,?,'user','合成岚桥风铃偏好',1)",source,conversation);
        db.update("INSERT INTO persona_memory VALUES (?,?,'viewer','合成岚桥风铃偏好',?,'CONFIRMED',1)",memory,person,source);
    }

    private void pause(CountDownLatch entered,CountDownLatch release) {
        var first=new AtomicBoolean(true);
        doAnswer(call->{
            Object selected=call.callRealMethod();
            if(first.compareAndSet(true,false)){entered.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("synthetic_recall_gate_timeout");}
            return selected;
        }).when(sql).recall(any(),anyString());
    }

    @Test void forgetCanAcquirePersonaNowaitWhileRecallPausedAndNoPayloadEscapes() throws Exception {
        String job=personas.chat("viewer",conversation,"岚桥风铃偏好").get("id").toString();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);pause(entered,release);
        var executor=Executors.newSingleThreadExecutor();var future=executor.submit(tasks::claim);
        try {
            assertTrue(entered.await(10,TimeUnit.SECONDS));
            assertDoesNotThrow(()->new TransactionTemplate(manager).executeWithoutResult(status->{
                db.queryForList("SELECT id FROM persona WHERE id=? FOR UPDATE NOWAIT",person);
                memories.forget("viewer",person,memory);
            }),"recall must not retain the persona mutation lock");
            release.countDown();assertNull(future.get(10,TimeUnit.SECONDS));
            assertEquals("CANCELLED",db.queryForObject("SELECT status FROM persona_job WHERE id=?",String.class,job));
            assertEquals("{}",db.queryForObject("SELECT payload FROM persona_job WHERE id=?",String.class,job));
        } finally {release.countDown();future.get(10,TimeUnit.SECONDS);executor.shutdownNow();assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS));}
    }

    @Test void anotherConversationOfSamePersonaCanClaimWhileFirstRecallPaused() throws Exception {
        personas.chat("viewer",conversation,"岚桥风铃偏好");
        String other=personas.start("owner",person,null).get("id").toString();
        String second=personas.chat("owner",other,"合成另一个独立问题").get("id").toString();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);pause(entered,release);
        var executor=Executors.newSingleThreadExecutor();var future=executor.submit(tasks::claim);
        try {
            assertTrue(entered.await(10,TimeUnit.SECONDS));
            var claimed=tasks.claim();assertNotNull(claimed,"unrelated conversation must not be skipped for the duration of recall");
            assertEquals(second,claimed.get("id"));
        } finally {release.countDown();assertNotNull(future.get(10,TimeUnit.SECONDS));executor.shutdownNow();assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS));}
    }

    private Map<String,Object> claimAcross(Runnable change) throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);pause(entered,release);
        var executor=Executors.newSingleThreadExecutor();var future=executor.submit(tasks::claim);
        try {assertTrue(entered.await(10,TimeUnit.SECONDS));change.run();release.countDown();return future.get(10,TimeUnit.SECONDS);}
        finally {release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS));}
    }

    private String queue(){return personas.chat("viewer",conversation,"岚桥风铃偏好").get("id").toString();}
    private String status(String job){return db.queryForObject("SELECT status FROM persona_job WHERE id=?",String.class,job);}

    @Test void membershipRevocationCommitsDuringRecallAndCancelsBeforeDelivery() throws Exception {
        String job=queue();
        assertNull(claimAcross(()->db.update("DELETE FROM member WHERE project_id=? AND user_id='viewer'",project)));
        assertEquals("CANCELLED",status(job));
        assertEquals("{}",db.queryForObject("SELECT payload FROM persona_job WHERE id=?",String.class,job));
    }

    @Test void correctionCancelsOldClaimAndFreshClaimResolvesCurrentSql() throws Exception {
        String job=queue();
        assertNull(claimAcross(()->memories.memory("viewer",person,memory,Map.of("status","CONFIRMED","body","合成岚桥风铃偏好已纠正"))));
        assertEquals("CANCELLED",status(job));
        String next=queue();var claimed=tasks.claim();assertEquals(next,claimed.get("id"));
        var payload=(Map<?,?>)claimed.get("payload");var refs=(List<?>)payload.get("memories");
        assertEquals(1,refs.size());assertEquals("合成岚桥风铃偏好已纠正",((Map<?,?>)refs.get(0)).get("body"));
    }

    @Test void deletedSourceConversationCannotReappearInClaim() throws Exception {
        queue();assertNull(claimAcross(()->personas.deleteConversation("viewer",conversation)));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM persona_job WHERE conversation_id=?",Integer.class,conversation));
    }

    @Test void replacedLeaseRejectsOldResultWithoutClearingReplacement() throws Exception {
        String job=queue();String replacement=UUID.randomUUID().toString();
        assertNull(claimAcross(()->db.update("UPDATE persona_job SET lease_token=? WHERE id=?",replacement,job)));
        assertEquals("RUNNING",status(job));assertEquals(replacement,db.queryForObject("SELECT lease_token FROM persona_job WHERE id=?",String.class,job));
    }

    @Test void expiredLeaseNeverDeliversAndCanBeReclaimed() throws Exception {
        String job=queue();assertNull(claimAcross(()->db.update("UPDATE persona_job SET lease_until=0 WHERE id=?",job)));
        assertEquals(job,tasks.claim().get("id"));
    }

    @Test void changedConversationVersionCancelsOldSelection() throws Exception {
        String job=queue();String version=UUID.randomUUID().toString();
        db.update("INSERT INTO persona_version(id,persona_id,status,content,creator,created_at) VALUES (?,?,'APPROVED','{}','owner',2)",version,person);
        assertNull(claimAcross(()->db.update("UPDATE persona_conversation SET version_id=? WHERE id=?",version,conversation)));
        assertEquals("CANCELLED",status(job));
    }

    @Test void providerFailureReleasesOnlyOwnedLeaseWithoutUsingAttempt() {
        String job=queue();doThrow(new IllegalStateException("synthetic_provider_failure")).when(sql).recall(any(),anyString());
        assertThrows(IllegalStateException.class,tasks::claim);assertEquals("QUEUED",status(job));
        assertEquals(0,db.queryForObject("SELECT attempt FROM persona_job WHERE id=?",Integer.class,job));
        doCallRealMethod().when(sql).recall(any(),anyString());assertEquals(job,tasks.claim().get("id"));
    }

    @Test void readOnlyRetrievalIsOutsideClaimLocksAndFinalResolutionRejectsStaleFingerprint() {
        queue();
        doAnswer(call->{
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            return call.callRealMethod();
        }).when(sql).recall(any(),anyString());
        var first=new AtomicBoolean(true);
        doAnswer(call->{
            Object rows=call.callRealMethod();
            if(first.compareAndSet(true,false)) {
                // Commit after the old resolver read, without normal mutation cancellation, to test the final guard.
                var changed=new TransactionTemplate(manager);changed.setPropagationBehavior(3);
                changed.executeWithoutResult(status->db.update("UPDATE persona_memory SET body='合成不匹配的新版本' WHERE id=?",memory));
            }
            return rows;
        }).when(memoryRepository).findConfirmedSource(memory,person,"viewer");
        var payload=(Map<?,?>)tasks.claim().get("payload");assertTrue(((List<?>)payload.get("memories")).isEmpty());
        verify(memoryRepository,times(2)).findConfirmedSource(memory,person,"viewer");
    }

    @Test void finalSqlResolutionHoldsMembershipAndPersonaLocksOnlyAfterSelection() {
        queue();var resolved=new AtomicBoolean();
        doAnswer(call->{
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            if(TransactionSynchronizationManager.isCurrentTransactionReadOnly())return call.callRealMethod();
            resolved.set(true);
            try(var connection=db.getDataSource().getConnection()) {
                for(String statement:List.of("SELECT id FROM persona WHERE id='"+person+"' FOR UPDATE NOWAIT",
                        "SELECT role FROM member WHERE project_id='"+project+"' AND user_id='viewer' FOR UPDATE NOWAIT")) {
                    try(var query=connection.prepareStatement(statement)) {
                        var denied=assertThrows(java.sql.SQLException.class,query::executeQuery);
                        assertEquals(50200,denied.getErrorCode());
                    }
                }
            }
            return call.callRealMethod();
        }).when(memoryRepository).findConfirmedSource(memory,person,"viewer");
        assertNotNull(tasks.claim());assertTrue(resolved.get());
    }

    @Test void pendingRecallDoesNotPermitDuplicateLeaseOfSameJob() throws Exception {
        String job=queue();
        var claimed=claimAcross(()->{
            assertNull(tasks.claim());assertEquals("RUNNING",status(job));
            assertEquals(1,db.queryForObject("SELECT attempt FROM persona_job WHERE id=?",Integer.class,job));
        });
        assertEquals(job,claimed.get("id"));
    }

    @Test void disabledMemoryStillSkipsProviderSelection() {
        personas.memoryEnabled("viewer",conversation,false);String job=queue();
        clearInvocations(sql);var claimed=tasks.claim();assertEquals(job,claimed.get("id"));
        verify(sql,never()).recall(any(),anyString());
        assertTrue(((List<?>)((Map<?,?>)claimed.get("payload")).get("memories")).isEmpty());
    }

    @Test void revokedCurrentLeaseCannotWriteDeltaAndClearsPreviousPartial() {
        String job=queue();var lease=tasks.claim();String token=lease.get("lease_token").toString();
        tasks.delta(job,token,"合成授权期间片段");
        db.update("DELETE FROM member WHERE project_id=? AND user_id='viewer'",project);
        var denied=assertThrows(ResponseStatusException.class,()->tasks.delta(job,token,"合成撤权后片段"));
        assertEquals(403,denied.getStatusCode().value());assertEquals("CANCELLED",status(job));
        assertEquals("",db.queryForObject("SELECT partial_reply FROM persona_job WHERE id=?",String.class,job));
        assertEquals("{}",db.queryForObject("SELECT payload FROM persona_job WHERE id=?",String.class,job));
    }

    @Test void staleDeltaCannotCancelReplacementButRevokedCurrentLeaseIsCancelled() {
        String job=queue();var first=tasks.claim();String old=first.get("lease_token").toString();
        db.update("UPDATE persona_job SET lease_until=0 WHERE id=?",job);
        var replacement=tasks.claim();String current=replacement.get("lease_token").toString();assertNotEquals(old,current);
        db.update("DELETE FROM member WHERE project_id=? AND user_id='viewer'",project);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->tasks.delta(job,old,"合成旧租约片段")).getStatusCode().value());
        assertEquals("RUNNING",status(job));assertEquals(current,db.queryForObject("SELECT lease_token FROM persona_job WHERE id=?",String.class,job));
        assertEquals(403,assertThrows(ResponseStatusException.class,()->tasks.delta(job,current,"合成撤权片段")).getStatusCode().value());
        assertEquals("CANCELLED",status(job));
    }

    @Test void correctionStopsCurrentDeltaAndFreshTaskCanStreamAndCompleteOnce() {
        String job=queue();var old=tasks.claim();String previous=old.get("lease_token").toString();
        memories.memory("viewer",person,memory,Map.of("status","CONFIRMED","body","合成岚桥风铃偏好已纠正"));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->tasks.delta(job,previous,"合成旧来源片段")).getStatusCode().value());
        String next=queue();var fresh=tasks.claim();String token=fresh.get("lease_token").toString();
        tasks.delta(next,token,"合成合法新片段");
        assertEquals("合成合法新片段",db.queryForObject("SELECT partial_reply FROM persona_job WHERE id=?",String.class,next));
        var output=Map.<String,Object>of("answer","合成合法完成","emotion",Map.of("label","calm","intensity",.3,"reason","合成"),"memory_candidates",List.of());
        assertEquals(tasks.complete(next,token,output),tasks.complete(next,token,output));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM persona_message WHERE conversation_id=? AND role='assistant'",Integer.class,conversation));
    }
}
