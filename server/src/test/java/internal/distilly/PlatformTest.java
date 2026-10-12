package internal.distilly;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(
        properties = { "spring.datasource.url=jdbc:h2:mem:platform-test;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "platform.demo=true", "platform.worker-token=test-worker-token-32-characters-long",
                "platform.max-active-tasks-per-user=0", "platform.task-submissions-per-minute=0", "platform.total-task-submissions-per-minute=0" })
@AutoConfigureMockMvc
class PlatformTest {

    @Autowired
    PlatformFixture service;

    @Autowired
    JdbcTemplate db;

    @Autowired
    MockMvc mvc;

    @Autowired
    internal.distilly.security.service.UserAccountService accounts;

    @Autowired
    internal.distilly.config.PlatformProperties properties;

    String project, profile, material;

    @BeforeEach
    void setup() {
        for (String table : List.of("job_archive", "job_progress", "profile_archive", "audit", "job", "profile_version",
                "profile", "material", "member", "project"))
            db.update("DELETE FROM " + table);
        project = (String) service.createProject("owner", "支付项目").get("id");
        service.member("owner", project, "editor", "EDITOR");
        service.member("owner", project, "viewer", "VIEWER");
        profile = (String) service.createProfile("editor", project, "接口评审经验").get("id");
        material = (String) service.upload("editor", project, "rules.md", "接口设计必须校验幂等键，重试不能重复扣款。").get("id");
    }

    Map<String, Object> output(Map<String, Object> job) {
        var payload = (Map<?, ?>) job.get("payload");
        var chunk = (Map<?, ?>) ((List<?>) payload.get("chunks")).get(0);
        return Map.of("claims", List.of(Map.of("section", "接口规范", "text", "接口设计应校验幂等键。", "evidence",
                List.of(Map.of("material_id", material, "chunk_id", chunk.get("chunk_id"), "quote", "必须校验幂等键")))));
    }

    String candidate() {
        service.distill("editor", profile, List.of(material));
        var job = service.claim();
        return (String) service.complete((String) job.get("id"), (String) job.get("lease_token"), output(job))
            .get("version_id");
    }

    @Test
    void paginationSearchAndRoleScopes() throws Exception {
        for (int i = 0; i < 205; i++)
            service.distill("editor", profile, List.of(material));
        var first = service.listPage("owner", project, "jobs", 1, 100, "接口", "QUEUED");
        var third = service.listPage("owner", project, "jobs", 3, 100, "editor", "QUEUED");
        assertEquals(205L, first.get("total"));
        assertEquals(100, ((List<?>) first.get("items")).size());
        assertEquals(5, ((List<?>) third.get("items")).size());
        assertEquals(0L, service.listPage("viewer", project, "jobs", 1, 10, "", "").get("total"));
        assertEquals(0L, service.listPage("owner", project, "jobs", 1, 10, "' OR 1=1 --", "").get("total"));
        assertThrows(ResponseStatusException.class, () -> service.listPage("owner", project, "jobs", 0, 10, "", ""));
        assertThrows(ResponseStatusException.class, () -> service.listPage("owner", project, "jobs", 1, 101, "", ""));
        service.upload("owner", project, "100%_ready.md", "测试材料");
        assertEquals(1L, service.listPage("editor", project, "materials", 1, 5, "%_", "").get("total"));
        assertThrows(ResponseStatusException.class,
                () -> service.listPage("viewer", project, "materials", 1, 10, "", ""));
        mvc.perform(get("/api/projects/" + project + "/jobs/page").param("page", "3")
            .param("size", "100")
            .header("Authorization", "Bearer demo-owner"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(205))
            .andExpect(jsonPath("$.items.length()").value(5));
    }

    @Test
    void archivedPaginationRespectsCreator() {
        String id = (String) service.distill("owner", profile, List.of(material)).get("id");
        service.controlJob("owner", id, "stop");
        service.deleteJob("owner", id);
        assertEquals(1L, service.listPage("owner", project, "archived-jobs", 1, 10, "", "").get("total"));
        assertEquals(0L, service.listPage("editor", project, "archived-jobs", 1, 10, "", "").get("total"));
    }

    @Test
    void deletionIsRecoverableAndChecksActiveJobs() {
        assertThrows(ResponseStatusException.class, () -> service.deleteProfile("editor", profile));
        service.distill("editor", profile, List.of(material));
        assertThrows(ResponseStatusException.class, () -> service.deleteProfile("owner", profile));
        var job = service.claim();
        service.complete((String) job.get("id"), (String) job.get("lease_token"), output(job));
        service.deleteProfile("owner", profile);
        assertTrue(service.profiles("owner", project).isEmpty());
        assertThrows(ResponseStatusException.class, () -> service.versions("owner", profile));
        assertEquals(1, service.archived("owner", project).size());
        service.restoreProfile("owner", profile);
        assertEquals(1, service.versions("owner", profile).size());
    }

    @Test
    void progressIsLeaseProtectedAndTaskListIsScoped() {
        service.distill("editor", profile, List.of(material));
        var j = service.claim();
        String id = (String) j.get("id"), token = (String) j.get("lease_token");
        service.progress(id, token, 1);
        assertEquals(1, ((Map<?, ?>) service.job("editor", id).get("progress")).get("completed"));
        assertThrows(ResponseStatusException.class, () -> service.progress(id, token, 2));
        assertThrows(ResponseStatusException.class, () -> service.progress(id, "wrong", 0));
        assertEquals(1, service.jobs("editor", project).size());
        assertTrue(service.jobs("viewer", project).isEmpty());
        assertFalse(service.jobs("editor", project).get(0).containsKey("payload"));
    }

    @Test
    void historyDeletionPreservesVersionsAndRestores() throws Exception {
        String version = candidate();
        String j = (String) service.jobs("owner", project).get(0).get("id");
        mvc.perform(delete("/api/jobs/" + j).header("Authorization", "Bearer demo-owner")).andExpect(status().isOk());
        assertTrue(service.jobs("owner", project).isEmpty());
        assertEquals(version, service.versions("owner", profile).get(0).get("id"));
        assertThrows(ResponseStatusException.class, () -> service.job("owner", j));
        assertEquals(1, service.archivedJobs("owner", project).size());
        service.restoreJob("owner", j);
        assertEquals("SUCCEEDED", service.job("owner", j).get("status"));
    }

    @Test
    void activeJobsCannotBeDeletedAndDeletedFailuresCannotRetry() {
        String j = (String) service.distill("editor", profile, List.of(material)).get("id");
        assertThrows(ResponseStatusException.class, () -> service.deleteJob("owner", j));
        service.claim();
        assertThrows(ResponseStatusException.class, () -> service.deleteJob("owner", j));
        db.update("UPDATE job SET status='FAILED' WHERE id=?", j);
        service.deleteJob("editor", j);
        assertThrows(ResponseStatusException.class, () -> service.retry("editor", j));
        service.restoreJob("editor", j);
        service.retry("editor", j);
        assertEquals("QUEUED", service.job("editor", j).get("status"));
    }

    @Test
    void historyDeletionChecksRolesAndProject() {
        String v = candidate();
        service.review("owner", v, true, "");
        String j = (String) service.question("viewer", profile, "接口规范？").get("id");
        db.update("UPDATE job SET status='SUCCEEDED' WHERE id=?", j);
        assertThrows(ResponseStatusException.class, () -> service.deleteJob("editor", j));
        service.deleteJob("viewer", j);
        assertEquals(1, service.archivedJobs("viewer", project).size());
        assertTrue(service.archivedJobs("editor", project).isEmpty());
        service.restoreJob("viewer", j);
        assertThrows(ResponseStatusException.class, () -> service.deleteJob("stranger", j));
    }

    @Test
    void pauseAndStopInvalidateLeaseAndResumeRequeues() {
        String j = (String) service.distill("editor", profile, List.of(material)).get("id");
        service.controlJob("editor", j, "pause");
        assertNull(service.claim());
        assertThrows(ResponseStatusException.class, () -> service.deleteJob("owner", j));
        assertThrows(ResponseStatusException.class, () -> service.deleteProfile("owner", profile));
        service.controlJob("editor", j, "resume");
        var worker = service.claim();
        String token = (String) worker.get("lease_token");
        service.controlJob("owner", j, "pause");
        assertThrows(ResponseStatusException.class, () -> service.complete(j, token, output(worker)));
        service.controlJob("editor", j, "resume");
        var second = service.claim();
        assertNotEquals(token, second.get("lease_token"));
        service.controlJob("editor", j, "stop");
        assertThrows(ResponseStatusException.class,
                () -> service.complete(j, (String) second.get("lease_token"), output(second)));
        assertEquals(0, service.versions("owner", profile).size());
        assertEquals("CANCELLED", service.job("owner", j).get("status"));
        assertThrows(ResponseStatusException.class, () -> service.controlJob("owner", j, "resume"));
        service.deleteJob("editor", j);
    }

    @Test
    void controlRequiresTaskOwnership() {
        String j = (String) service.distill("owner", profile, List.of(material)).get("id");
        assertThrows(ResponseStatusException.class, () -> service.controlJob("editor", j, "pause"));
        assertThrows(ResponseStatusException.class, () -> service.controlJob("viewer", j, "stop"));
        service.controlJob("owner", j, "stop");
    }

    @Test
    void fullLifecycleAndControlledExport() throws Exception {
        String version = candidate();
        assertTrue(service.versions("viewer", profile).isEmpty());
        service.review("owner", version, true, "");
        assertEquals(version, service.published("viewer", profile).get("id"));
        mvc.perform(get("/api/profiles/" + profile + "/download").header("Authorization", "Bearer demo-viewer"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Profile-Version", version))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("token"))))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("接口设计应校验幂等键")));
        service.question("viewer", profile, "重试要注意什么？");
        var job = service.claim();
        var claims = (List<Map<String, Object>>) ((Map<?, ?>) job.get("payload")).get("claims");
        service.complete((String) job.get("id"), (String) job.get("lease_token"),
                Map.of("answer", "检查幂等键。", "citations", List.of(claims.get(0).get("id"))));
        assertEquals("SUCCEEDED", service.job("viewer", (String) job.get("id")).get("status"));
    }

    @Test
    void authAndProjectIsolation() throws Exception {
        mvc.perform(get("/api/projects")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/materials/" + material).header("Authorization", "Bearer demo-viewer"))
            .andExpect(status().isForbidden());
        String other = (String) service.createProject("viewer", "独立项目").get("id");
        assertThrows(ResponseStatusException.class, () -> service.materials("owner", other));
        assertThrows(ResponseStatusException.class, () -> service.distill("viewer", profile, List.of(material)));
        assertThrows(ResponseStatusException.class, () -> service.review("editor", candidate(), true, ""));
    }

    @Test
    void invalidEvidenceCannotCreateCandidate() {
        service.distill("editor", profile, List.of(material));
        var job = service.claim();
        var bad = Map.<String, Object>of("claims", List.of(Map.of("section", "规则", "text", "未支持的结论", "evidence",
                List.of(Map.of("material_id", material, "chunk_id", material + ":0", "quote", "编造的原文内容")))));
        assertThrows(ResponseStatusException.class,
                () -> service.complete((String) job.get("id"), (String) job.get("lease_token"), bad));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM profile_version", Integer.class));
        assertEquals("RUNNING", service.job("editor", (String) job.get("id")).get("status"));
    }

    @Test
    void duplicateCompletionIsIdempotent() {
        service.distill("editor", profile, List.of(material));
        var job = service.claim();
        var first = service.complete((String) job.get("id"), (String) job.get("lease_token"), output(job));
        assertEquals(first, service.complete((String) job.get("id"), (String) job.get("lease_token"), output(job)));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM profile_version", Integer.class));
    }

    @Test
    void leaseExpiryRejectsOldWorkerAndRecovers() {
        service.distill("editor", profile, List.of(material));
        var first = service.claim();
        String j = (String) first.get("id");
        db.update("UPDATE job SET lease_until=0 WHERE id=?", j);
        var second = service.claim();
        assertNotEquals(first.get("lease_token"), second.get("lease_token"));
        assertThrows(ResponseStatusException.class,
                () -> service.complete(j, (String) first.get("lease_token"), output(first)));
        service.complete(j, (String) second.get("lease_token"), output(second));
        assertEquals(2, service.job("editor", j).get("attempt"));
    }

    @Test
    void staleCandidateCannotOverwriteNewPublication() {
        String first = candidate(), second = candidate();
        service.review("owner", first, true, "");
        assertThrows(ResponseStatusException.class, () -> service.review("owner", second, true, ""));
        assertEquals(first, service.published("viewer", profile).get("id"));
        assertEquals("CANDIDATE",
                service.versions("owner", profile)
                    .stream()
                    .filter(v -> v.get("id").equals(second))
                    .findFirst()
                    .orElseThrow()
                    .get("status"));
    }

    @Test
    void appendAndRollbackPreserveImmutableHistory() {
        String first = candidate();
        service.review("owner", first, true, "");
        String before = (String) service.published("owner", profile).get("content");
        String second = candidate();
        service.review("owner", second, true, "");
        service.rollback("owner", profile, first);
        assertEquals(before, service.published("viewer", profile).get("content"));
        assertEquals(2, service.versions("owner", profile).size());
    }

    @Test
    void uploadsRejectBinaryWrongTypeAndCrossProjectSelection() throws Exception {
        mvc.perform(multipart("/api/projects/" + project + "/materials")
            .file(new MockMultipartFile("file", "bad.md", "text/markdown", new byte[] { (byte) 0xff }))
            .header("Authorization", "Bearer demo-editor")).andExpect(status().isUnprocessableEntity());
        assertThrows(ResponseStatusException.class, () -> service.upload("editor", project, "data.pdf", "x"));
        String other = (String) service.createProject("owner", "其他项目").get("id");
        String foreign = (String) service.upload("owner", other, "other.md", "其他项目独有资料").get("id");
        assertThrows(ResponseStatusException.class, () -> service.distill("owner", profile, List.of(foreign)));
    }

    @Test
    void personalAndWorkerTokensAuthorizeSeparateEndpointFamilies() throws Exception {
        String personal = "fixture-owner-private-token-at-least-32-characters";
        try {
            properties.setDemo(false);
            properties.setUsers("[{\"id\":\"owner\",\"token\":\"" + personal + "\"}]");
            accounts.configureUsers();
            mvc.perform(get("/api/me").header("Authorization", "Bearer " + personal))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("owner"));
            mvc.perform(post("/internal/jobs/claim").header("Authorization", "Bearer " + personal)
                .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
            mvc.perform(get("/api/me").header("Authorization", "Bearer " + properties.getWorkerToken()))
                .andExpect(status().isUnauthorized());
            mvc.perform(post("/internal/jobs/claim").header("Authorization", "Bearer " + properties.getWorkerToken())
                .contentType("application/json").content("{}"))
                .andExpect(status().isNoContent());
        }
        finally {
            properties.setDemo(true);
            properties.setUsers("[]");
            accounts.configureUsers();
        }
    }

    @Test
    void leavingDemoModeRevokesOldDemoTokens() throws Exception {
        try {
            properties.setDemo(false);
            properties.setUsers("[{\"id\":\"owner\",\"token\":\"configured-owner-token-at-least-32-characters\"}]");
            accounts.configureUsers();
            mvc.perform(get("/api/projects").header("Authorization", "Bearer demo-owner"))
                .andExpect(status().isUnauthorized());
            mvc.perform(get("/api/projects").header("Authorization", "Bearer demo-viewer"))
                .andExpect(status().isUnauthorized());
            mvc.perform(get("/api/projects").header("Authorization",
                    "Bearer configured-owner-token-at-least-32-characters"))
                .andExpect(status().isOk());
        }
        finally {
            properties.setDemo(true);
            properties.setUsers("[]");
            accounts.configureUsers();
        }
    }

    @Test
    void retriesStopAtConfiguredLimit() {
        String j = (String) service.distill("editor", profile, List.of(material)).get("id");
        for (int i = 0; i < 3; i++) {
            var claimed = service.claim();
            assertNotNull(claimed);
            service.fail(j, (String) claimed.get("lease_token"), "MODEL_UNAVAILABLE");
        }
        assertEquals("FAILED", service.job("editor", j).get("status"));
        assertNull(service.claim());
        service.retry("editor", j);
        assertEquals("QUEUED", service.job("editor", j).get("status"));
    }


    @Test
    void topicTaskPageSeparatesTopicsAndProtectsCandidates() throws Exception {
        String version = candidate();
        String other = (String) service.createProfile("owner", project, "其他专题").get("id");
        service.distill("owner", other, List.of(material));
        mvc.perform(get("/api/profiles/" + profile + "/jobs/page").header("Authorization", "Bearer demo-owner"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].version_status").value("CANDIDATE"));
        service.review("owner", version, true, "核对来源");
        mvc.perform(get("/api/profiles/" + profile + "/jobs/page").header("Authorization", "Bearer demo-editor"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].version_status").value("APPROVED"));
        mvc.perform(get("/api/profiles/" + profile + "/jobs/page").header("Authorization", "Bearer demo-viewer"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/profiles/" + profile + "/jobs/page?size=0").header("Authorization", "Bearer demo-owner"))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void deletingApprovedTaskWithdrawsDescendantsAndPreservesEarlierKnowledge() throws Exception {
        String first = candidate();
        service.review("owner", first, true, "");
        String second = candidate();
        service.review("owner", second, true, "");
        String third = candidate();
        String task = db.queryForObject("SELECT id FROM job WHERE result LIKE ?", String.class, "%" + second + "%");
        String endpoint = "/api/profiles/" + profile + "/jobs/" + task + "/experience";
        mvc.perform(delete(endpoint).header("Authorization", "Bearer demo-editor"))
            .andExpect(status().isForbidden());
        String unrelated = (String) service.createProfile("owner", project, "另一专题").get("id");
        mvc.perform(delete("/api/profiles/" + unrelated + "/jobs/" + task + "/experience")
                .header("Authorization", "Bearer demo-owner")).andExpect(status().isNotFound());
        mvc.perform(delete(endpoint).header("Authorization", "Bearer demo-owner")).andExpect(status().isOk());
        assertEquals("APPROVED", db.queryForObject("SELECT status FROM profile_version WHERE id=?", String.class, first));
        for (String removed : List.of(second, third))
            assertEquals("DELETED", db.queryForObject("SELECT status FROM profile_version WHERE id=?", String.class, removed));
        assertNull(db.queryForObject("SELECT published_id FROM profile WHERE id=?", String.class, profile));
        assertEquals(1, service.versions("owner", profile).size());
        assertThrows(ResponseStatusException.class, () -> service.published("viewer", profile));
        assertThrows(ResponseStatusException.class, () -> service.rollback("owner", profile, second));
        assertThrows(ResponseStatusException.class, () -> service.review("owner", third, true, ""));
        service.restoreJob("owner", task);
        assertEquals("DELETED", service.job("owner", task).get("version_status"));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM material WHERE id=?", Integer.class, material));
        service.rollback("owner", profile, first);
        assertEquals(first, service.published("viewer", profile).get("id"));
    }

    @Test
    void knowledgeDeletionRequiresApprovedVersionAndNoActiveTasks() throws Exception {
        String first = candidate();
        String task = db.queryForObject("SELECT id FROM job WHERE result LIKE ?", String.class, "%" + first + "%");
        String endpoint = "/api/profiles/" + profile + "/jobs/" + task + "/experience";
        mvc.perform(delete(endpoint).header("Authorization", "Bearer demo-owner")).andExpect(status().isConflict());
        service.review("owner", first, true, "");
        String active = (String) service.distill("owner", profile, List.of(material)).get("id");
        mvc.perform(delete(endpoint).header("Authorization", "Bearer demo-owner")).andExpect(status().isConflict());
        assertEquals(first, service.published("viewer", profile).get("id"));
        service.controlJob("owner", active, "stop");
        mvc.perform(delete(endpoint).header("Authorization", "Bearer demo-owner")).andExpect(status().isOk());
        mvc.perform(delete(endpoint).header("Authorization", "Bearer demo-owner")).andExpect(status().isConflict());
    }
}
