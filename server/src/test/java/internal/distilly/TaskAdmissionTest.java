package internal.distilly;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import internal.distilly.modules.task.service.WorkTaskService;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:task-admission;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "platform.demo=true", "platform.worker-token=admission-worker-token-at-least-32-characters",
        "platform.max-active-tasks-per-user=2", "platform.task-submissions-per-minute=0", "platform.total-task-submissions-per-minute=0"})
@AutoConfigureMockMvc
class TaskAdmissionTest {
    @Autowired PlatformFixture platform;
    @Autowired PersonaFixture personas;
    @Autowired WorkTaskService work;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    String project, topic, material, persona;

    @BeforeEach
    void setup() {
        for (String table : List.of("persona_knowledge", "persona_job", "persona_memory", "persona_message", "persona_conversation", "persona_version", "persona",
                "job_chunk_result", "job_distill_execution", "job_archive", "job_progress", "profile_archive", "audit", "job", "profile_version", "profile", "material", "member", "project")) db.update("DELETE FROM " + table);
        project = (String) platform.createProject("owner", "合成任务准入").get("id");
        platform.member("owner", project, "editor", "EDITOR");
        platform.member("owner", project, "viewer", "VIEWER");
        topic = (String) platform.createProfile("owner", project, "合成主题").get("id");
        material = (String) platform.upload("owner", project, "fixture.md", "合成材料用于核对准入事务。").get("id");
        persona = (String) personas.create("owner", project, Map.of("name", "合成人物", "family", "relationship", "origin", "original",
                "expression", "短句", "personality", "温和", "boundaries", "不推断经历", "background", "原创人物", "consent", false)).get("id");
    }

    String submit(String actor) { return (String) platform.distill(actor, topic, List.of(material)).get("id"); }
    String image(String actor) { return (String) personas.generateImage(actor, persona, "合成测试形象").get("id"); }
    void limited(Runnable action) {
        var error = assertThrows(ResponseStatusException.class, action::run);
        assertEquals(429, error.getStatusCode().value());
        assertEquals("user_active_task_limit", error.getReason());
    }
    String state(String table, String id) { return db.queryForObject("SELECT status FROM " + table + " WHERE id=?", String.class, id); }

    @Test
    void personaAndWorkShareAllowanceWithoutChargingOtherUsers() {
        submit("owner"); image("owner");
        limited(() -> submit("owner"));
        assertNotNull(submit("editor"));
        assertNotNull(image("editor"));
        limited(() -> submit("editor"));
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM job", Integer.class));
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM persona_job", Integer.class));
    }

    @Test
    void changingWorkspaceCannotBypassTheSameUserAllowance() {
        submit("owner"); image("owner");
        String other = (String) platform.createProject("owner", "另一合成空间").get("id");
        String profile = (String) platform.createProfile("owner", other, "另一主题").get("id");
        String source = (String) platform.upload("owner", other, "other.md", "另一空间合成材料").get("id");
        limited(() -> platform.distill("owner", profile, List.of(source)));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM job WHERE project_id=?", Integer.class, other));
    }

    @Test
    void pauseRetainsAllowanceAndResumeDoesNotReserveAnotherSlot() {
        String first = submit("owner"); submit("owner");
        platform.controlJob("owner", first, "pause");
        limited(() -> image("owner"));
        platform.controlJob("owner", first, "resume");
        assertEquals("QUEUED", state("job", first));
        platform.controlJob("owner", first, "stop");
        assertNotNull(image("owner"));
        limited(() -> submit("owner"));
    }

    @Test
    void ownerRetryUsesOriginalCreatorAllowance() {
        String failed = submit("editor");
        var lease = platform.claim();
        platform.fail(failed, (String) lease.get("lease_token"), "INVALID_MODEL_OUTPUT");
        String active = submit("editor"); image("editor");
        limited(() -> platform.retry("owner", failed));
        assertEquals("FAILED", state("job", failed));
        platform.controlJob("editor", active, "stop");
        platform.retry("owner", failed);
        assertEquals("QUEUED", state("job", failed));
        assertEquals("editor", db.queryForObject("SELECT creator FROM job WHERE id=?", String.class, failed));
    }

    @Test
    void personaRetryCannotBypassAllowance() {
        String failed = image("editor");
        var lease = personas.claim();
        personas.fail(failed, (String) lease.get("lease_token"), "INVALID_MODEL_OUTPUT");
        submit("editor"); String active = image("editor");
        limited(() -> personas.retry("owner", failed));
        assertEquals("FAILED", state("persona_job", failed));
        personas.cancel("editor", active);
        personas.retry("owner", failed);
        assertEquals("QUEUED", state("persona_job", failed));
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> personas.retry("owner", failed)).getStatusCode().value());
    }

    @Test
    void automaticRequeueAndLeaseReleaseRetainSlotsWhileTerminalFailureFreesOne() {
        String first = submit("owner"); submit("owner");
        var lease = platform.claim();
        platform.fail(first, (String) lease.get("lease_token"), "MODEL_UNAVAILABLE");
        limited(() -> image("owner"));
        lease = platform.claim();
        platform.fail(first, (String) lease.get("lease_token"), "INVALID_MODEL_OUTPUT");
        String added = image("owner");
        var second = platform.claim();
        work.release((String) second.get("id"), (String) second.get("lease_token"));
        limited(() -> submit("owner"));
        personas.cancel("owner", added);
        assertNotNull(submit("owner"));
    }

    @Test
    void restoringTerminalHistoryDoesNotQueueWorkAndRetryIsStillChecked() {
        String failed = submit("owner"); var lease = platform.claim();
        platform.fail(failed, (String) lease.get("lease_token"), "INVALID_MODEL_OUTPUT");
        platform.deleteJob("owner", failed);
        submit("owner"); image("owner");
        platform.restoreJob("owner", failed);
        assertEquals("FAILED", state("job", failed));
        limited(() -> platform.retry("owner", failed));
    }

    @Test
    void rejectedChatRollsBackItsUserMessageAndHttpShowsStableError() throws Exception {
        personas.generate("owner", persona, List.of()); var lease = personas.claim();
        String version = (String) personas.complete((String) lease.get("id"), (String) lease.get("lease_token"), Map.of("persona_markdown", "# 合成人物\n短句，未知事实说明不足。", "analysis", Map.of(), "evidence", List.of())).get("version_id");
        personas.review("owner", persona, version, true);
        String conversation = (String) personas.start("owner", persona, null).get("id");
        submit("owner"); image("owner");
        limited(() -> personas.chat("owner", conversation, "不会落库的合成输入"));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM persona_message WHERE conversation_id=?", Integer.class, conversation));
        mvc.perform(post("/api/personas/" + persona + "/image").header("Authorization", "Bearer demo-owner")
                .contentType("application/json").content("{\"prompt\":\"合成测试\"}"))
            .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error").value("user_active_task_limit"));
    }

    @Test
    void concurrentQueuesCannotOverbookUserAllowance() throws Exception {
        submit("owner");
        var start = new CountDownLatch(1); var executor = Executors.newFixedThreadPool(6);
        try {
            var results = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 6; i++) {
                boolean personaQueue = i % 2 == 0;
                results.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try { if (personaQueue) image("owner"); else submit("owner"); return 200; }
                    catch (ResponseStatusException error) { return error.getStatusCode().value(); }
                }));
            }
            start.countDown();
            var statuses = new ArrayList<Integer>();
            for (var result : results) statuses.add(result.get(10, TimeUnit.SECONDS));
            assertEquals(1, statuses.stream().filter(code -> code == 200).count());
            assertEquals(5, statuses.stream().filter(code -> code == 429).count());
        }
        finally { start.countDown(); executor.shutdownNow(); }
    }
}
