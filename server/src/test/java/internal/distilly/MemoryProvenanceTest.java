package internal.distilly;

import internal.distilly.modules.memory.service.MemoryService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:memory-provenance;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "platform.demo=true", "platform.worker-token=memory-provenance-synthetic-worker-32-char"})
class MemoryProvenanceTest {
    @Autowired MemoryService memories;
    @Autowired PersonaFixture personas;
    @Autowired PlatformFixture platform;
    @Autowired JdbcTemplate db;
    String project,persona,conversation,source,memory;
    @BeforeEach void setup() {
        project=platform.createProject("owner","合成记忆来源").get("id").toString();
        platform.member("owner",project,"viewer","VIEWER");
        persona=personas.create("owner",project,Map.of("name","合成","family","relationship","origin","original")).get("id").toString();
        // Source fixtures exercise the same constrained rows without a model request.
        String version=UUID.randomUUID().toString();conversation=UUID.randomUUID().toString();source=UUID.randomUUID().toString();memory=UUID.randomUUID().toString();
        db.update("INSERT INTO persona_version(id,persona_id,base_id,status,content,creator,created_at) VALUES (?, ?,NULL,'APPROVED','{}','owner',1)",version,persona);
        db.update("INSERT INTO persona_conversation(id,persona_id,version_id,user_id,title,emotion,created_at) VALUES (?,?,?,'viewer','合成来源交流','{}',1)",conversation,persona,version);
        db.update("INSERT INTO persona_message(id,conversation_id,role,body,created_at) VALUES (?,?,'user','合成偏好：喜欢海边拍照',1)",source,conversation);
        db.update("INSERT INTO persona_memory VALUES (?,?,'viewer','喜欢海边拍照',?,'PENDING',1)",memory,persona,source);
    }
    Map<String,Object> row() { return memories.memories("viewer",persona).get(0); }
    void status(int code,Runnable action) { assertEquals(code,assertThrows(ResponseStatusException.class,action::run).getStatusCode().value()); }
    @Test void sourceIsTraceableOutsideCurrentSessionButPrivateToItsUser() {
        var origin=memories.source("viewer",persona,memory);
        assertEquals(source,origin.get("message_id"));assertEquals(conversation,origin.get("conversation_id"));
        assertEquals("合成偏好：喜欢海边拍照",origin.get("body"));
        status(404,()->memories.source("owner",persona,memory));
    }
    @Test void staleCorrectionCannotOverwriteConfirmedNewBody() {
        var before=row();
        memories.memory("viewer",persona,memory,Map.of("status","CONFIRMED","body","喜欢周六海边拍照","expected_revision",before.get("revision")));
        status(409,()->memories.memory("viewer",persona,memory,Map.of("status","CONFIRMED","body","旧页面修改","expected_revision",before.get("revision"))));
        assertEquals("喜欢周六海边拍照",row().get("body"));assertNotEquals(before.get("revision"),row().get("revision"));
    }
    @Test void statusChangeConflictsAndLegacyUpdatesRemainCompatible() {
        var old=row();memories.memory("viewer",persona,memory,Map.of("status","REJECTED"));
        status(409,()->memories.memory("viewer",persona,memory,Map.of("status","CONFIRMED","expected_revision",old.get("revision"))));
        memories.memory("viewer",persona,memory,Map.of("status","CONFIRMED","expected_revision",row().get("revision")));
        assertEquals("CONFIRMED",row().get("status"));
    }
    @Test void forgottenMemoryHasNoSourceEndpointAndDoesNotLeakViaOwner() {
        memories.forget("viewer",persona,memory);
        status(404,()->memories.source("viewer",persona,memory));assertTrue(memories.memories("viewer",persona).isEmpty());
    }
    @Test void tamperedForeignSourceCannotBeReturned() {
        db.update("UPDATE persona_conversation SET user_id='owner' WHERE id=?",conversation);
        status(404,()->memories.source("viewer",persona,memory));
    }
    @Test void membershipRevocationBlocksSourceRead() {
        db.update("DELETE FROM member WHERE project_id=? AND user_id='viewer'",project);
        status(403,()->memories.source("viewer",persona,memory));
    }
}
