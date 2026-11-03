package internal.distilly;

import internal.distilly.common.json.JsonCodec;
import internal.distilly.modules.memory.domain.MemoryProvider;
import internal.distilly.modules.memory.service.SqlMemoryProvider;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import static internal.distilly.common.validation.InputChecks.items;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:memory-reference;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "platform.demo=true","platform.worker-token=reference-synthetic-worker-token-32-characters"})
@AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
class MemoryReferenceTest {
    @Autowired PlatformFixture platform;
    @Autowired PersonaFixture personas;
    @Autowired JdbcTemplate db;
    @Autowired JsonCodec json;
    @Autowired MockMvc mvc;
    @MockitoSpyBean SqlMemoryProvider sql;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    String project,person,conversation;
    String query="雾砂灯座青藤电池";
    String original="  合成参考：雾砂灯座禁止使用青藤电池。\n<b>只按文本展示。</b>  ";

    @BeforeEach void setup(){
        project=platform.createProject("owner","合成原文参考").get("id").toString();
        platform.member("owner",project,"viewer","VIEWER");
        person=createPerson(project);conversation=personas.start("viewer",person,null).get("id").toString();
    }
    String createPerson(String workspace){
        String p=personas.create("owner",workspace,Map.of("name","合成参考人物","family","relationship","origin","original")).get("id").toString();
        String v=UUID.randomUUID().toString();db.update("INSERT INTO persona_version(id,persona_id,status,content,creator,created_at) VALUES (?,?,'APPROVED',?,'owner',1)",v,p,json.encode(Map.of("persona_markdown","合成角色。")));db.update("UPDATE persona SET published_id=? WHERE id=?",v,p);return p;
    }
    String seed(String actor,String p,String c,String body,String status){
        String source=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();
        db.update("INSERT INTO persona_message(id,conversation_id,role,body,created_at) VALUES (?,?,'user',?,1)",source,c,"合成最初消息，可能与纠错后的记忆记录不同。");
        db.update("INSERT INTO persona_memory VALUES (?,?,?,?,?,?,1)",id,p,actor,body,source,status);return id;
    }
    ResultActions request(String actor,Map<String,Object> body)throws Exception{
        return mvc.perform(post("/api/personas/"+person+"/memory-references").header("Authorization","Bearer demo-"+actor)
                .contentType(MediaType.APPLICATION_JSON).content(json.encode(body)));
    }
    Map<String,Object> read(String actor)throws Exception{
        var r=request(actor,Map.of("mode","SOURCE_REFERENCE","query",query)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store")).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)).andReturn();
        assertFalse(r.getRequest().isAsyncStarted());return json.decode(r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }
    Map<String,Long> counts(){var result=new LinkedHashMap<String,Long>();for(String t:List.of("persona_job","persona_message","persona_memory","persona_conversation"))result.put(t,db.queryForObject("SELECT COUNT(*) FROM "+t,Long.class));return result;}
    @Test void explicitJsonModeReturnsOnlyCurrentLiteralRecordAndNoWrites()throws Exception{
        String id=seed("viewer",person,conversation,original,"CONFIRMED");var before=counts();var result=read("viewer");
        assertEquals(Set.of("contract","mode","support_status","notice","references"),result.keySet());
        assertEquals("MEMORY_REFERENCE_V1",result.get("contract"));assertEquals("SOURCE_REFERENCE",result.get("mode"));
        assertEquals("NOT_ASSESSED",result.get("support_status"));assertTrue(result.get("notice").toString().contains("相关不等于支持"));
        var refs=items(result.get("references"));assertEquals(1,refs.size());var row=refs.get(0);
        assertEquals(Set.of("memory_id","source_message_id","record_text","record_fingerprint","quote_kind"),row.keySet());
        assertEquals(id,row.get("memory_id"));assertTrue(Objects.equals(original,row.get("record_text")),"literal SQL record unchanged");assertEquals("CONFIRMED_MEMORY_RECORD",row.get("quote_kind"));
        assertEquals(MemoryProvider.reference(db.queryForMap("SELECT * FROM persona_memory WHERE id=?",id)).revision(),row.get("record_fingerprint"));assertEquals(before,counts());
    }
    @Test void exactModeRequiredAndWorkerClaimsOrStreamFlagsCannotBeInjected()throws Exception{
        for(var body:List.<Map<String,Object>>of(Map.of("query",query),Map.of("mode","CHAT","query",query),Map.of("mode","SOURCE_REFERENCE","query",""),Map.of("mode","SOURCE_REFERENCE","query",4)))request("viewer",body).andExpect(status().isUnprocessableEntity());
        for(String key:List.of("answer","support_status","stream","memory_ids","bank_id","user_id")){
            var body=new LinkedHashMap<String,Object>(Map.of("mode","SOURCE_REFERENCE","query",query));body.put(key,"synthetic injected field");request("viewer",body).andExpect(status().isUnprocessableEntity());
        }
        mvc.perform(post("/api/personas/"+person+"/memory-references").header("Authorization","Bearer demo-viewer").contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM).content(json.encode(Map.of("mode","SOURCE_REFERENCE","query",query)))).andExpect(status().isNotAcceptable());
    }
    @Test void newModeCannotFallThroughToExistingChatOrCreatePartialJobs()throws Exception{
        var before=counts();for(String key:List.of("mode","answer_mode"))mvc.perform(post("/api/conversations/"+conversation+"/messages").header("Authorization","Bearer demo-viewer")
                .contentType(MediaType.APPLICATION_JSON).content(json.encode(Map.of("message",query,key,"SOURCE_REFERENCE")))).andExpect(status().isUnprocessableEntity());assertEquals(before,counts());
        mvc.perform(post("/api/conversations/"+conversation+"/messages").header("Authorization","Bearer demo-viewer")
                .contentType(MediaType.APPLICATION_JSON).content(json.encode(Map.of("message","合成普通交流")))).andExpect(status().isOk());
        assertEquals(before.get("persona_job")+1,counts().get("persona_job"));
    }
    @Test void privateScopeAndConfirmedUserSourceAreRequired()throws Exception{
        String own=seed("viewer",person,conversation,original,"CONFIRMED");seed("viewer",person,conversation,original,"PENDING");seed("viewer",person,conversation,original,"REJECTED");
        String owners=personas.start("owner",person,null).get("id").toString();String ownerId=seed("owner",person,owners,original,"CONFIRMED");
        String other=createPerson(project),otherConversation=personas.start("viewer",other,null).get("id").toString();seed("viewer",other,otherConversation,original,"CONFIRMED");
        assertEquals(List.of(own),items(read("viewer").get("references")).stream().map(r->r.get("memory_id")).toList());
        assertEquals(List.of(ownerId),items(read("owner").get("references")).stream().map(r->r.get("memory_id")).toList());request("editor",Map.of("mode","SOURCE_REFERENCE","query",query)).andExpect(status().isForbidden());
        mvc.perform(post("/api/personas/"+person+"/memory-references").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
    }
    @Test void refreshAfterCorrectionAndForgetUsesFreshSqlAndKeepsHistoryPolicy()throws Exception{
        String id=seed("viewer",person,conversation,original,"CONFIRMED");String first=items(read("viewer").get("references")).get(0).get("record_fingerprint").toString();
        String corrected="合成纠错：雾砂灯座禁止使用青藤电池，先核对说明。";personas.memory("viewer",person,id,Map.of("status","CONFIRMED","body",corrected));
        var updated=items(read("viewer").get("references")).get(0);assertTrue(Objects.equals(corrected,updated.get("record_text")),"current correction only");assertNotEquals(first,updated.get("record_fingerprint"));
        personas.forget("viewer",person,id);assertTrue(items(read("viewer").get("references")).isEmpty());assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM persona_message WHERE conversation_id=?",Integer.class,conversation));
    }
    @Test void deletionOfSourceAndRevocationPreventRefresh()throws Exception{
        seed("viewer",person,conversation,original,"CONFIRMED");assertEquals(1,items(read("viewer").get("references")).size());personas.deleteConversation("viewer",conversation);assertTrue(items(read("viewer").get("references")).isEmpty());
        db.update("DELETE FROM member WHERE project_id=? AND user_id='viewer'",project);request("viewer",Map.of("mode","SOURCE_REFERENCE","query",query)).andExpect(status().isForbidden());
    }
    @Test void selectedFingerprintIsRecheckedBeforeAnyReferenceIsReturned()throws Exception{
        String id=seed("viewer",person,conversation,original,"CONFIRMED");
        doAnswer(call->{Object selected=call.callRealMethod();db.update("UPDATE persona_memory SET body=? WHERE id=?","合成纠错后的另一条正文。",id);return selected;}).when(sql).recall(any(),eq(query));
        var r=request("viewer",Map.of("mode","SOURCE_REFERENCE","query",query)).andExpect(status().isConflict()).andReturn();assertFalse(r.getResponse().getContentAsString().contains(original));
    }
    @Test void permissionIsRecheckedEvenIfChangedDuringSelection()throws Exception{
        seed("viewer",person,conversation,original,"CONFIRMED");doAnswer(call->{Object selected=call.callRealMethod();db.update("DELETE FROM member WHERE project_id=? AND user_id='viewer'",project);return selected;}).when(sql).recall(any(),eq(query));
        request("viewer",Map.of("mode","SOURCE_REFERENCE","query",query)).andExpect(status().isForbidden());
    }
    @Test void malformedAssistantSourceCannotBeRenderedAsConfirmedUserRecord()throws Exception{
        String id=seed("viewer",person,conversation,original,"CONFIRMED");db.update("UPDATE persona_message SET role='assistant' WHERE id=(SELECT source_id FROM persona_memory WHERE id=?)",id);
        request("viewer",Map.of("mode","SOURCE_REFERENCE","query",query)).andExpect(status().isConflict());
    }
    void overlappingMutation(boolean revoke)throws Exception{
        String id=seed("viewer",person,conversation,original,"CONFIRMED");var selected=new CountDownLatch(1);var release=new CountDownLatch(1);var changing=new CountDownLatch(1);var first=new AtomicBoolean(true);
        doAnswer(call->{Object refs=call.callRealMethod();if(first.compareAndSet(true,false)){selected.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("reference_test_timeout");}return refs;}).when(sql).recall(any(),eq(query));
        var pool=Executors.newFixedThreadPool(2);
        try{
            var reading=pool.submit(()->read("viewer"));assertTrue(selected.await(10,TimeUnit.SECONDS));
            var mutation=pool.submit(()->{changing.countDown();if(revoke)new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx->db.update("DELETE FROM member WHERE project_id=? AND user_id='viewer'",project));else personas.memory("viewer",person,id,Map.of("status","CONFIRMED","body","合成更新：雾砂灯座禁止使用青藤电池。"));return true;});
            assertTrue(changing.await(10,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->mutation.get(250,TimeUnit.MILLISECONDS));release.countDown();
            var snapshot=reading.get(10,TimeUnit.SECONDS);assertTrue(Objects.equals(original,items(snapshot.get("references")).get(0).get("record_text")),"authorized pre-mutation snapshot");assertTrue(mutation.get(10,TimeUnit.SECONDS));
            if(revoke)request("viewer",Map.of("mode","SOURCE_REFERENCE","query",query)).andExpect(status().isForbidden());
            else assertFalse(Objects.equals(original,items(read("viewer").get("references")).get(0).get("record_text")),"refresh reads committed correction");
        }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }
    @Test void correctionAndReadAreOrderedSnapshots()throws Exception{overlappingMutation(false);}
    @Test void revocationAndReadAreOrderedSnapshots()throws Exception{overlappingMutation(true);}
}
