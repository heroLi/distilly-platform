package internal.distilly;

import internal.distilly.modules.personality.service.PersonalityService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:binding-retry;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "platform.demo=true", "platform.worker-token=binding-retry-synthetic-worker-token-32-characters"})
class PersonalityBindingRetryTest {
    @Autowired PlatformFixture platform;
    @Autowired PersonaFixture personas;
    @Autowired PersonalityService bindings;
    @Autowired JdbcTemplate db;
    String project, target, source, version;
    Map<String, Object> definition() {
        return Map.of("name", "合成伙伴", "family", "colleague", "origin", "original", "personality", "简洁",
                "background", "合成资料，无真人信息", "consent", false);
    }
    String publish(String id) {
        personas.generate("owner", id, List.of()); var job = personas.claim();
        String version = personas.complete(job.get("id").toString(), job.get("lease_token").toString(),
                Map.of("persona_markdown", "# 合成人格\n核对来源。", "analysis", Map.of("inferences", List.of()),
                        "evidence", List.of())).get("version_id").toString();
        personas.review("owner", id, version, true); return version;
    }
    @BeforeEach void setup() {
        project = platform.createProject("owner", "合成绑定重试").get("id").toString();
        platform.member("owner", project, "editor", "EDITOR");
        target = personas.create("owner", project, definition()).get("id").toString();
        source = bindings.createPersonality("owner", project, definition()).get("id").toString();
        version = publish(source);
    }
    long versions() { return db.queryForObject("SELECT COUNT(*) FROM persona_version WHERE persona_id=?", Long.class, target); }
    long audits() { return db.queryForObject("SELECT COUNT(*) FROM audit WHERE project_id=? AND action='PERSONALITY_BOUND'", Long.class, project); }
    String bind() { return bindings.bindPersonality("owner", target, source, version).get("published_id").toString(); }
    @Test void repeatedSameBindingAfterLostAckReturnsTheExistingVersion() {
        String first = bind(); assertEquals(first, bind()); assertEquals(1, versions()); assertEquals(1, audits());
    }
    @Test void concurrentSameBindingCreatesOnlyOneVersionAndAudit() throws Exception {
        var gate = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> { gate.await(); return bind(); });
            var b = pool.submit(() -> { gate.await(); return bind(); }); gate.countDown();
            assertEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertEquals(1, versions()); assertEquals(1, audits());
        } finally { pool.shutdownNow(); }
    }
    @Test void changedTargetDefinitionProducesANewSnapshotRatherThanBeingDiscarded() {
        String first = bind(); var changed = new java.util.LinkedHashMap<>(definition()); changed.put("expression", "耐心解释");
        personas.update("owner", target, changed); String next = bind();
        assertNotEquals(first, next); assertEquals(next, bind()); assertEquals(2, versions());
    }
    @Test void newSourceVersionChangesNewConversationsButPreservesOldOnes() {
        String first = bind(); String conversation = personas.start("owner", target, null).get("id").toString();
        version = publish(source); String next = bind(); assertNotEquals(first, next);
        assertEquals(first, personas.session("owner", conversation).get("version_id")); assertEquals(next, bind());
    }
    @Test void retryDoesNotBypassPermissionOrDeletedAssetChecks() {
        bind(); assertThrows(ResponseStatusException.class, () -> bindings.bindPersonality("editor", target, source, version));
        db.update("UPDATE persona SET deleted=TRUE WHERE id=?", source);
        assertThrows(ResponseStatusException.class, this::bind); assertEquals(1, versions()); assertEquals(1, audits());
    }
}
