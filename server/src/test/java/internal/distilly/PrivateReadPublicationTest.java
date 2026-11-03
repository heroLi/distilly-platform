package internal.distilly;

import internal.distilly.common.json.JsonCodec;
import internal.distilly.modules.workspace.service.WorkspaceService;
import internal.distilly.modules.persona.service.*;
import internal.distilly.modules.conversation.service.ConversationService;
import internal.distilly.modules.conversation.repository.ConversationRepository;
import internal.distilly.modules.memory.service.MemoryService;
import internal.distilly.modules.memory.repository.MemoryRepository;
import internal.distilly.modules.task.service.PersonaTaskService;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"server.address=127.0.0.1","spring.datasource.url=jdbc:h2:mem:private-read-publication;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "platform.demo=true","platform.worker-token=private-read-publication-synthetic-token-32"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class PrivateReadPublicationTest {
    @Autowired WorkspaceService workspaces;
    @Autowired PersonaService personas;
    @Autowired ConversationService conversations;
    @Autowired MemoryService memories;
    @Autowired PersonaTaskService tasks;
    @Autowired JdbcTemplate db;
    @LocalServerPort int port;
    static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    @Autowired JsonCodec json;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean PersonaAccessService access;
    @MockitoSpyBean MemoryRepository memoryRows;
    @MockitoSpyBean ConversationRepository conversationRows;
    String project,person,conversation,sourceConversation,source,memory,version;
    final String old="合成记录：雪竹灯座保留清晨安排。",updated="合成记录：雪竹灯座已更正为午后安排。";
    final String origin="合成原始用户消息：雪竹灯座清晨。",partial="合成尚未完成的私人片段。";
    CountDownLatch selected,release;AtomicBoolean once;

    @BeforeEach void setup(){
        project=workspaces.createProject("owner","合成最终读取复核").get("id").toString();workspaces.member("owner",project,"viewer","VIEWER");
        person=personas.create("owner",project,Map.of("name","合成复核人物","family","relationship","origin","original")).get("id").toString();
        version=anotherVersion();db.update("UPDATE persona SET published_id=? WHERE id=?",version,person);
        sourceConversation=conversations.start("viewer",person,null).get("id").toString();conversation=conversations.start("viewer",person,null).get("id").toString();
        source=UUID.randomUUID().toString();memory=UUID.randomUUID().toString();
        db.update("INSERT INTO persona_message(id,conversation_id,role,body,created_at) VALUES (?,?,'user',?,1)",source,sourceConversation,origin);
        db.update("INSERT INTO persona_memory VALUES (?,?,'viewer',?,?,'CONFIRMED',1)",memory,person,old,source);
        selected=new CountDownLatch(1);release=new CountDownLatch(1);once=new AtomicBoolean(true);
    }
    String anotherVersion(){String id=UUID.randomUUID().toString();db.update("INSERT INTO persona_version(id,persona_id,status,content,creator,created_at) VALUES (?,?,'APPROVED','{}','owner',1)",id,person);return id;}
    Object pause(Object value)throws Exception{if(once.compareAndSet(true,false)){selected.countDown();assertTrue(release.await(10,TimeUnit.SECONDS),"synthetic read gate timed out");}return value;}
    void atAuth(String kind)throws Exception{
        if(kind.equals("conversation"))doAnswer(c->pause(c.callRealMethod())).when(access).conversation("viewer",conversation);
        else doAnswer(c->pause(c.callRealMethod())).when(access).persona("viewer",person);
    }
    void atRows(String kind)throws Exception{
        if(kind.equals("memories"))doAnswer(c->pause(c.callRealMethod())).when(memoryRows).findByPersonaAndUser(person,"viewer");
        else if(kind.equals("source"))doAnswer(c->pause(c.callRealMethod())).when(memoryRows).findSource(person,"viewer",memory);
        else doAnswer(c->pause(c.callRealMethod())).when(conversationRows).findActiveTasks(conversation);
    }
    String path(String kind){return kind.equals("conversation")?"/api/conversations/"+conversation:"/api/personas/"+person+"/memories"+(kind.equals("source")?"/"+memory+"/source":"");}
    HttpResponse<String> request(String kind,String actor)throws Exception{var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path(kind))).timeout(Duration.ofSeconds(20));if(actor!=null)r.header("Authorization","Bearer demo-"+actor);return HTTP.send(r.GET().build(),HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));}
    HttpResponse<String> read(String kind)throws Exception{return request(kind,"viewer");}
    HttpResponse<String> overlap(String kind,Runnable mutation,int expected)throws Exception{
        var pool=Executors.newSingleThreadExecutor();try{
            var reading=pool.submit(()->read(kind));assertTrue(selected.await(10,TimeUnit.SECONDS),"read did not reach gate");mutation.run();release.countDown();
            var result=reading.get(10,TimeUnit.SECONDS);assertEquals(expected,result.statusCode());
            if(expected>=400){String body=result.body();assertFalse(body.contains(old));assertFalse(body.contains(origin));assertFalse(body.contains(partial));}
            return result;
        }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }
    void revoke(){db.update("DELETE FROM member WHERE project_id=? AND user_id='viewer'",project);}
    void correct(){memories.memory("viewer",person,memory,Map.of("status","CONFIRMED","body",updated));}
    String body(HttpResponse<String> r)throws Exception{return r.body();}
    Map<String,Object> active(){var job=conversations.chat("viewer",conversation,"雪竹灯座");var claim=tasks.claim();assertEquals(job.get("id"),claim.get("id"));tasks.delta(claim.get("id").toString(),claim.get("lease_token").toString(),partial);return claim;}

    @Test void conversationRevokedAfterAuthorizationIsNotPublished()throws Exception{atAuth("conversation");overlap("conversation",this::revoke,403);}
    @Test void memoryListRevokedAfterAuthorizationIsNotPublished()throws Exception{atAuth("memories");overlap("memories",this::revoke,403);}
    @Test void sourceRevokedAfterAuthorizationIsNotPublished()throws Exception{atAuth("source");overlap("source",this::revoke,403);}
    @Test void archivedPersonaCannotPublishConversation()throws Exception{atRows("conversation");overlap("conversation",()->personas.archive("owner",person),404);}
    @Test void archivedPersonaCannotPublishMemory()throws Exception{atRows("memories");overlap("memories",()->personas.archive("owner",person),404);}
    @Test void archivedPersonaCannotPublishSource()throws Exception{atRows("source");overlap("source",()->personas.archive("owner",person),404);}
    @Test void conversationRevokedAfterDataSelectionIsNotPublished()throws Exception{atRows("conversation");overlap("conversation",this::revoke,403);}
    @Test void correctedCapturedMemoryFailsClosed()throws Exception{atRows("memories");overlap("memories",this::correct,409);}
    @Test void rejectedCapturedMemoryFailsClosed()throws Exception{atRows("memories");overlap("memories",()->memories.memory("viewer",person,memory,Map.of("status","REJECTED")),409);}
    @Test void deletedCapturedMemoryFailsClosed()throws Exception{atRows("memories");overlap("memories",()->memories.forget("viewer",person,memory),409);}
    @Test void deletedSourceCannotPublishOldMessage()throws Exception{atRows("source");overlap("source",()->conversations.deleteConversation("viewer",sourceConversation),404);}
    @Test void sourceVersionFaultAfterSelectionFailsClosed()throws Exception{String next=anotherVersion();atRows("source");overlap("source",()->db.update("UPDATE persona_conversation SET version_id=? WHERE id=?",next,sourceConversation),409);}
    @Test void changedConversationPinnedVersionFailsClosed()throws Exception{String next=anotherVersion();atRows("conversation");overlap("conversation",()->db.update("UPDATE persona_conversation SET version_id=? WHERE id=?",next,conversation),409);}
    @Test void changedSourceLinkFailsClosedEvenWhenRecordBodyIsSame()throws Exception{
        String next=UUID.randomUUID().toString();db.update("INSERT INTO persona_message(id,conversation_id,role,body,created_at) VALUES (?,?,'user',?,2)",next,sourceConversation,origin);
        atRows("memories");overlap("memories",()->db.update("UPDATE persona_memory SET source_id=? WHERE id=?",next,memory),409);
    }
    @Test void sourceCorrectionCancellingTaskCannotPublishCapturedPartial()throws Exception{active();atRows("conversation");overlap("conversation",this::correct,409);}
    @Test void normalCompletionAllowsPreviouslyAuthorizedPartialPrefix()throws Exception{
        var job=active();atRows("conversation");var result=overlap("conversation",()->tasks.complete(job.get("id").toString(),job.get("lease_token").toString(),Map.of("answer","合成完成答复。","emotion",Map.of("label","calm","intensity",.3,"reason","合成"),"memory_candidates",List.of())),200);
        assertTrue(body(result).contains(partial));
        assertFalse(body(result).contains("read_claim"));assertFalse(body(result).contains("read_attempt"));assertFalse(body(result).contains(job.get("lease_token").toString()),"internal claim must not be published");
    }
    @Test void queuedTaskCanBeClaimedNormallyWhileSnapshotIsRead()throws Exception{
        var queued=conversations.chat("viewer",conversation,"雪竹灯座");atRows("conversation");
        overlap("conversation",()->{var claim=tasks.claim();assertEquals(queued.get("id"),claim.get("id"));tasks.complete(claim.get("id").toString(),claim.get("lease_token").toString(),Map.of("answer","合成正常完成。","emotion",Map.of("label","calm","intensity",.3,"reason","合成"),"memory_candidates",List.of()));},200);
    }
    @Test void retriedNewClaimWithResetAttemptCannotPublishOldSourcePartial()throws Exception{
        var first=active();atRows("conversation");overlap("conversation",()->{
            tasks.fail(first.get("id").toString(),first.get("lease_token").toString(),"MODEL_CONFIGURATION_ERROR");correct();tasks.retry("viewer",first.get("id").toString());
            var next=tasks.claim();assertEquals(first.get("id"),next.get("id"));assertFalse(Objects.equals(first.get("lease_token"),next.get("lease_token")),"retry must use a new claim");
            tasks.delta(next.get("id").toString(),next.get("lease_token").toString(),"合成新来源片段。");tasks.complete(next.get("id").toString(),next.get("lease_token").toString(),Map.of("answer","合成新来源答复。","emotion",Map.of("label","calm","intensity",.3,"reason","合成"),"memory_candidates",List.of()));
        },409);
    }
    @Test void revocationAfterFinalStatementDoesNotPretendToRetractSnapshot()throws Exception{
        doAnswer(c->pause(c.callRealMethod())).when(memoryRows).findForPublication(person,"viewer");var result=overlap("memories",this::revoke,200);assertTrue(body(result).contains(old));assertEquals(403,read("memories").statusCode());
    }
    @Test void correctionAfterFinalStatementUsesOldAuthorizedSnapshotThenNewRead()throws Exception{
        doAnswer(c->pause(c.callRealMethod())).when(memoryRows).findForPublication(person,"viewer");var result=overlap("memories",this::correct,200);assertTrue(body(result).contains(old));assertTrue(body(read("memories")).contains(updated));
    }
    @Test void appendedMemoryDoesNotInvalidateExistingValidSnapshot()throws Exception{
        atRows("memories");var result=overlap("memories",()->db.update("INSERT INTO persona_memory VALUES (?,?,'viewer',?,?,'PENDING',2)",UUID.randomUUID().toString(),person,"合成新增候选。",source),200);
        assertTrue(body(result).contains(old));assertFalse(body(result).contains("合成新增候选。"));
    }
    @Test void publishedPointerChangeKeepsExistingConversationVersion()throws Exception{
        String next=anotherVersion();atRows("conversation");var result=overlap("conversation",()->db.update("UPDATE persona SET published_id=? WHERE id=?",next,person),200);assertEquals(version,json.decode(body(result)).get("version_id"));
    }
    @Test void correctingRecordDoesNotRewriteOriginalSourcePayload()throws Exception{
        atRows("source");var result=overlap("source",this::correct,200);var map=json.decode(body(result));assertEquals(origin,map.get("body"));assertEquals(Set.of("message_id","conversation_id","body","created_at","title"),map.keySet());
    }
    @Test void emptyAndStableListsPreserveShapeAndRevision()throws Exception{
        var list=memories.memories("viewer",person);assertEquals(1,list.size());assertEquals(64,list.get(0).get("revision").toString().length());memories.forget("viewer",person,memory);assertTrue(memories.memories("viewer",person).isEmpty());assertEquals(200,read("conversation").statusCode());
    }
    @Test void nestedWriteCanReadItsUncommittedNewConversation()throws Exception{
        var created=new TransactionTemplate(transactions).execute(tx->{db.queryForMap("SELECT id FROM persona WHERE id=? FOR UPDATE",person);return conversations.start("viewer",person,null);});assertNotNull(created);assertEquals(person,created.get("persona_id"));
    }
    @Test void finalChecksDoNotWaitForExistingWriterRowLocks()throws Exception{
        var held=new CountDownLatch(1);var done=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{var writer=pool.submit(()->new TransactionTemplate(transactions).executeWithoutResult(tx->{db.queryForMap("SELECT role FROM member WHERE project_id=? AND user_id='viewer' FOR UPDATE",project);db.queryForMap("SELECT id FROM persona WHERE id=? FOR UPDATE",person);db.queryForMap("SELECT id FROM persona_conversation WHERE id=? FOR UPDATE",conversation);held.countDown();try{assertTrue(done.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}}));
            assertTrue(held.await(10,TimeUnit.SECONDS));var reader=pool.submit(()->{assertEquals(200,read("conversation").statusCode());assertEquals(200,read("memories").statusCode());assertEquals(200,read("source").statusCode());return null;});reader.get(8,TimeUnit.SECONDS);done.countDown();writer.get(10,TimeUnit.SECONDS);
        }finally{done.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }
    @Test void otherActorsAndUnauthenticatedRequestsStayIsolated()throws Exception{
        assertEquals(403,request("conversation","owner").statusCode());
        assertEquals(404,request("source","owner").statusCode());
        assertEquals(401,request("memories",null).statusCode());assertTrue(memories.memories("owner",person).isEmpty());
    }
}
