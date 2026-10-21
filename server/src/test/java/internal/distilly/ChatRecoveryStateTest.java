package internal.distilly;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:chat-recovery;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "platform.demo=true", "platform.worker-token=chat-recovery-synthetic-worker-token-32-characters"})
class ChatRecoveryStateTest {
    @Autowired PlatformFixture platform;
    @Autowired PersonaFixture personas;
    @Autowired JdbcTemplate db;
    String project, person, conversation;
    @AfterEach void stopSyntheticJobs() {
        db.update("UPDATE persona_job SET status='CANCELLED',lease_token=NULL WHERE persona_id=? AND status IN ('QUEUED','RUNNING')",person);
    }
    @BeforeEach void setup() {
        project=platform.createProject("owner", "合成聊天恢复").get("id").toString();
        platform.member("owner",project,"viewer","VIEWER");
        person=personas.create("owner",project,Map.of("name","合成伙伴","family","colleague","origin","original","consent",false)).get("id").toString();
        personas.generate("owner",person,List.of());var job=personas.claim();
        var version=personas.complete(job.get("id").toString(),job.get("lease_token").toString(),Map.of("persona_markdown","合成设定","analysis",Map.of(),"evidence",List.of()));
        personas.review("owner",person,version.get("version_id").toString(),true);
        conversation=personas.start("viewer",person,null).get("id").toString();
    }
    @SuppressWarnings("unchecked") Map<String,Object> latest(){return (Map<String,Object>)personas.session("viewer",conversation).get("latest_chat_job");}
    @Test void emptyAndFailedRetryStatesRemainVisibleWithoutDuplicatingInput(){
        assertNull(latest());String id=personas.chat("viewer",conversation,"合成失败").get("id").toString();
        var leased=personas.claim();personas.fail(id,leased.get("lease_token").toString(),"MODEL_CONFIGURATION_ERROR");
        assertEquals(Map.of("id",id,"status","FAILED","error_code","MODEL_CONFIGURATION_ERROR","attempt",1),latest());
        assertEquals(4,latest().size());personas.retry("viewer",id);assertEquals("QUEUED",latest().get("status"));
        assertThrows(ResponseStatusException.class,()->personas.retry("viewer",id));
        assertEquals(1,((List<?>)personas.session("viewer",conversation).get("messages")).size());
    }
    @Test void cancelledTurnStaysCancelledAndLateCompletionCannotReviveIt(){
        String id=personas.chat("viewer",conversation,"合成取消").get("id").toString();var leased=personas.claim();
        personas.cancel("viewer",id);personas.cancel("viewer",id);assertEquals("CANCELLED",latest().get("status"));
        assertThrows(ResponseStatusException.class,()->personas.complete(id,leased.get("lease_token").toString(),Map.of("answer","晚到合成回答")));
        assertEquals(1,((List<?>)personas.session("viewer",conversation).get("messages")).size());
    }
    @Test void currentConversationStateNeverExposesOtherUsersOrRevokedMembership(){
        personas.chat("viewer",conversation,"仅属合成阅读者");
        assertThrows(ResponseStatusException.class,()->personas.session("owner",conversation));
        assertThrows(ResponseStatusException.class,()->personas.cancel("owner",latest().get("id").toString()));
        db.update("DELETE FROM member WHERE project_id=? AND user_id=?",project,"viewer");
        assertThrows(ResponseStatusException.class,()->personas.session("viewer",conversation));
    }
    @Test void latestStateIsConversationScopedEvenBeyondThePersonTaskPage(){
        String old=personas.chat("viewer",conversation,"合成旧轮").get("id").toString();personas.cancel("viewer",old);
        String other=personas.start("viewer",person,null).get("id").toString();
        for(int i=0;i<105;i++) db.update("INSERT INTO persona_job(id,persona_id,conversation_id,kind,status,payload,creator,created_at) VALUES(?,?,?,'CHAT','CANCELLED','{}','viewer',?)","synthetic-page-"+i,person,other,System.currentTimeMillis()+1000+i);
        assertEquals(old,latest().get("id"));assertEquals("CANCELLED",latest().get("status"));
    }
}
