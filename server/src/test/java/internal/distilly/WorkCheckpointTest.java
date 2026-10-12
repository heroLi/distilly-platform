package internal.distilly;

import internal.distilly.common.json.JsonCodec;
import internal.distilly.modules.task.service.WorkTaskService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = { "spring.datasource.url=jdbc:h2:mem:checkpoint-test;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "platform.demo=true", "platform.worker-token=test-worker-token-32-characters-long" })
@AutoConfigureMockMvc
class WorkCheckpointTest {

    @Autowired PlatformFixture platform;
    @Autowired WorkTaskService tasks;
    @Autowired JdbcTemplate db;
    @Autowired JsonCodec json;
    @Autowired MockMvc mvc;

    String jobId, topicId, projectId;
    static final String FINGERPRINT = "1".repeat(64);

    @BeforeEach
    void setup() {
        for (String table : List.of("job_archive", "job_progress", "profile_archive", "audit", "job", "profile_version",
                "profile", "material", "member", "project"))
            db.update("DELETE FROM " + table);
        String project = (String) platform.createProject("owner", "断点验收").get("id");
        projectId = project;
        topicId = (String) platform.createProfile("owner", project, "接口评审经验").get("id");
        var chunks = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 6; i++) {
            String material = (String) platform.upload("owner", project, "fixture" + i + ".md", "第" + i + "段接口须校验幂等键。").get("id");
            chunks.add(Map.of("material_id", material, "chunk_id", material + ":0", "text", "第" + i + "段接口须校验幂等键。"));
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("title", "接口评审经验");
        payload.put("chunks", chunks);
        payload.put("base_id", null);
        payload.put("base_claims", List.of());
        jobId = (String) platform.enqueue("owner", project, topicId, "DISTILL", payload).get("id");
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> chunks(Map<String, Object> job) {
        return (List<Map<String, Object>>) ((Map<?, ?>) job.get("payload")).get("chunks");
    }

    List<Map<String, Object>> claims(Map<String, Object> chunk) {
        return List.of(Map.of("section", "接口", "text", chunk.get("text"), "evidence", List.of(Map.of(
                "material_id", chunk.get("material_id"), "chunk_id", chunk.get("chunk_id"), "quote", chunk.get("text")))));
    }

    Map<String, Object> save(Map<String, Object> job, int index) {
        var chunk = chunks(job).get(index);
        return tasks.completeChunk(jobId, (String) job.get("lease_token"), (String) chunk.get("chunk_id"),
                (String) chunk.get("input_digest"), claims(chunk));
    }

    void bind(Map<String, Object> job) {
        tasks.bindExecution(jobId, (String) job.get("lease_token"), FINGERPRINT);
    }

    void completed(int expected) {
        assertEquals(expected, ((Map<?, ?>) tasks.job("owner", jobId).get("progress")).get("completed"));
        assertEquals(expected, tasks.job("owner", jobId).get("checkpoint_count"));
    }

    @Test
    void fifthChunkSurvivesFailurePauseAndResume() {
        var first = tasks.claim();
        bind(first);
        for (int i = 0; i < 5; i++) save(first, i);
        tasks.fail(jobId, (String) first.get("lease_token"), "MODEL_UNAVAILABLE");
        var retry = tasks.claim();
        completed(5);
        assertEquals(5, ((List<?>) retry.get("checkpoints")).size());
        assertEquals(FINGERPRINT, retry.get("execution_fingerprint"));
        tasks.controlJob("owner", jobId, "pause");
        assertThrows(ResponseStatusException.class, () -> save(retry, 5));
        tasks.controlJob("owner", jobId, "resume");
        var resumed = tasks.claim();
        bind(resumed);
        completed(5);
        assertThrows(ResponseStatusException.class, () -> save(first, 5));
        save(resumed, 5);
        var result = tasks.complete(jobId, (String) resumed.get("lease_token"), Map.of("checkpointed", true));
        assertEquals(6, result.get("claim_count"));
        assertEquals("SUCCEEDED", tasks.job("owner", jobId).get("status"));
        assertEquals(result, tasks.complete(jobId, (String) resumed.get("lease_token"), Map.of("checkpointed", true)));
        assertEquals(1, platform.versions("owner", topicId).size());
    }

    @Test
    void replayIsIdempotentAndConflictingOrForgedResultsCannotReplaceCheckpoint() {
        var job = tasks.claim();
        bind(job);
        assertEquals(true, save(job, 0).get("saved"));
        assertEquals(false, save(job, 0).get("saved"));
        completed(1);
        var chunk = chunks(job).get(0);
        var changed = new LinkedHashMap<>(claims(chunk).get(0));
        changed.put("text", "另一个有来源的结论");
        assertThrows(ResponseStatusException.class, () -> tasks.completeChunk(jobId, (String) job.get("lease_token"),
                (String) chunk.get("chunk_id"), (String) chunk.get("input_digest"), List.of(changed)));
        assertThrows(ResponseStatusException.class, () -> tasks.completeChunk(jobId, (String) job.get("lease_token"),
                (String) chunk.get("chunk_id"), "0".repeat(64), claims(chunk)));
        var other = chunks(job).get(1);
        assertThrows(ResponseStatusException.class, () -> tasks.completeChunk(jobId, (String) job.get("lease_token"),
                (String) other.get("chunk_id"), (String) other.get("input_digest"), claims(chunk)));
        assertThrows(ResponseStatusException.class, () -> tasks.complete(jobId, (String) job.get("lease_token"), Map.of("claims", claims(chunk))));
        completed(1);
        assertEquals("RUNNING", tasks.job("owner", jobId).get("status"));
        for (int i = 1; i < 6; i++) save(job, i);
        // Once execution is checkpointed, completion only uses stored validated claims.
        var result = tasks.complete(jobId, (String) job.get("lease_token"), Map.of("claims", List.of()));
        assertEquals(6, result.get("claim_count"));
        assertThrows(ResponseStatusException.class, () -> save(job, 0));
    }

    @Test
    void expiredLeaseAndStoppedTaskRejectLateWritesWithoutPublishing() {
        var first = tasks.claim();
        bind(first);
        save(first, 0);
        db.update("UPDATE job SET lease_until=0 WHERE id=?", jobId);
        assertThrows(ResponseStatusException.class, () -> save(first, 1));
        var replacement = tasks.claim();
        assertNotEquals(first.get("lease_token"), replacement.get("lease_token"));
        completed(1);
        assertEquals(1, ((List<?>) replacement.get("checkpoints")).size());
        tasks.controlJob("owner", jobId, "stop");
        assertThrows(ResponseStatusException.class, () -> save(replacement, 1));
        assertThrows(ResponseStatusException.class, () -> tasks.complete(jobId, (String) replacement.get("lease_token"), Map.of("checkpointed", true)));
        assertTrue(platform.versions("owner", topicId).isEmpty());
        assertNull(tasks.claim());
    }

    @Test
    void executionChangeFailsWithoutMixingConfigurationsAndExplicitRetryRetainsBinding() {
        var first = tasks.claim();
        bind(first);
        save(first, 0);
        assertThrows(ResponseStatusException.class, () -> tasks.bindExecution(jobId, (String) first.get("lease_token"), "2".repeat(64)));
        tasks.fail(jobId, (String) first.get("lease_token"), "EXECUTION_CHANGED");
        assertEquals("FAILED", tasks.job("owner", jobId).get("status"));
        assertNull(tasks.claim());
        tasks.retry("owner", jobId);
        var retried = tasks.claim();
        assertEquals(FINGERPRINT, retried.get("execution_fingerprint"));
        assertEquals(1, ((List<?>) retried.get("checkpoints")).size());
        bind(retried);
    }

    @Test
    void preflightChecksScopeAndShowsSizeWithoutEnqueueing() throws Exception {
        var frozen = json.decode(db.queryForObject("SELECT payload FROM job WHERE id=?", String.class, jobId));
        var selected = internal.distilly.common.validation.InputChecks.items(frozen.get("chunks")).stream()
                .map(chunk -> chunk.get("material_id")).toList();
        String path = "/api/projects/" + projectId + "/distill/preflight";
        String body = json.encode(Map.of("profile_id", topicId, "material_ids", selected));
        mvc.perform(post(path).header("Authorization", "Bearer demo-owner").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.material_count").value(6))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.chunk_count").value(6))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.base_claim_count").value(0));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM job", Integer.class));
        platform.member("owner", projectId, "viewer", "VIEWER");
        mvc.perform(post(path).header("Authorization", "Bearer demo-viewer").contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        String foreign = (String) platform.createProject("owner", "其他空间").get("id");
        mvc.perform(post("/api/projects/" + foreign + "/distill/preflight").header("Authorization", "Bearer demo-owner")
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        String tooLarge = (String) platform.upload("owner", projectId, "large.md", "甲".repeat(200001)).get("id");
        mvc.perform(post(path).header("Authorization", "Bearer demo-owner").contentType("application/json")
                .content(json.encode(Map.of("material_ids", List.of(tooLarge))))).andExpect(status().isUnprocessableEntity());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM job", Integer.class));
    }

    @Test
    void ordinaryUserCannotCallCheckpointEndpointAndWorkerCanSaveEmptyValidatedChunk() throws Exception {
        var job = tasks.claim();
        bind(job);
        var chunk = chunks(job).get(0);
        String body = json.encode(Map.of("lease_token", job.get("lease_token"), "chunk_id", chunk.get("chunk_id"),
                "input_digest", chunk.get("input_digest"), "claims", List.of()));
        mvc.perform(post("/internal/jobs/" + jobId + "/chunks/complete").header("Authorization", "Bearer demo-owner")
                .contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/jobs/" + jobId + "/chunks/complete").header("Authorization", "Bearer test-worker-token-32-characters-long")
                .contentType("application/json").content(body)).andExpect(status().isOk());
        completed(1);
    }
    @Test
    void deterministicFailureRequiresManualRetryAndKeepsValidatedCheckpoints() {
        var first = tasks.claim(); bind(first); save(first, 0);
        tasks.fail(jobId, (String) first.get("lease_token"), "INVALID_MODEL_OUTPUT");
        assertEquals("FAILED", tasks.job("owner", jobId).get("status"));
        assertNull(tasks.claim());
        tasks.retry("owner", jobId);
        assertEquals(1, ((List<?>) tasks.claim().get("checkpoints")).size());
    }

    @Test
    void gracefulReleaseKeepsFrozenCheckpointAndRejectsStoppedOrStaleLease() throws Exception {
        var first = tasks.claim(); bind(first); save(first, 0);
        String path = "/internal/jobs/" + jobId + "/release";
        String body = "{\"lease_token\":\"" + first.get("lease_token") + "\"}";
        for (int n = 0; n < 2; n++)
            mvc.perform(post(path).header("Authorization", "Bearer test-worker-token-32-characters-long").contentType("application/json").content(body)).andExpect(status().isOk());
        assertEquals(0, tasks.job("owner", jobId).get("attempt")); completed(1);
        var next = tasks.claim(); assertNotEquals(first.get("lease_token"), next.get("lease_token"));
        assertEquals(1, ((List<?>) next.get("checkpoints")).size());
        assertThrows(ResponseStatusException.class, () -> save(first, 1));
        tasks.controlJob("owner", jobId, "stop");
        mvc.perform(post(path).header("Authorization", "Bearer test-worker-token-32-characters-long").contentType("application/json")
                .content("{\"lease_token\":\"" + next.get("lease_token") + "\"}")).andExpect(status().isConflict());
    }

}
