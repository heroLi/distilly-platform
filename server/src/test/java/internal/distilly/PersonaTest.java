package internal.distilly;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(
        properties = { "spring.datasource.url=jdbc:h2:mem:persona-test;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "platform.demo=true", "platform.worker-token=test-worker-token-32-characters-long" })
@AutoConfigureMockMvc
class PersonaTest {

    @Autowired
    PersonaFixture service;

    @Autowired
    internal.distilly.modules.knowledge.service.PersonaKnowledgeService knowledge;

    @Autowired
    PlatformFixture base;

    @Autowired
    JdbcTemplate db;

    @Autowired
    MockMvc mvc;

    String project, persona;

    Map<String, Object> definition() {
        return new LinkedHashMap<>(Map.of("name", "小溪", "family", "relationship", "origin", "original", "personality",
                "温和，有自己的判断", "expression", "简洁自然", "background", "原创朋友", "relationship", "朋友", "boundaries", "尊重隐私",
                "consent", true));
    }

    @BeforeEach
    void setup() {
        for (String t : List.of("persona_knowledge", "persona_job", "persona_memory", "persona_message",
                "persona_conversation", "persona_version", "persona", "job_archive", "job_progress", "profile_archive",
                "audit", "job", "profile_version", "profile", "material", "member", "project"))
            db.update("DELETE FROM " + t);
        project = (String) base.createProject("owner", "人物空间").get("id");
        base.member("owner", project, "editor", "EDITOR");
        base.member("owner", project, "viewer", "VIEWER");
        persona = (String) service.create("owner", project, definition()).get("id");
    }

    Map<String, Object> generation() {
        return Map.of("persona_markdown", "# 小溪\n温和、有自己的判断。", "analysis",
                Map.of("expression", "自然", "inferences", List.of()), "evidence", List.of());
    }

    String candidate() {
        service.generate("owner", persona, List.of());
        var job = service.claim();
        return (String) service.complete((String) job.get("id"), (String) job.get("lease_token"), generation())
            .get("version_id");
    }

    String publish() {
        String v = candidate();
        service.review("owner", persona, v, true);
        return v;
    }

    Map<String, Object> reply() {
        return Map.of("answer", "我记住你的偏好了，确认后我们再用它。", "emotion",
                Map.of("label", "warm", "intensity", 0.6, "reason", "用户分享了自己的偏好"), "memory_candidates",
                List.of(Map.of("body", "用户喜欢安静散步", "quote", "喜欢安静散步")));
    }

    String chat(String actor, String c) {
        return (String) service.chat(actor, c, "请记住，我喜欢安静散步。").get("id");
    }

    void completeReply() {
        var j = service.claim();
        service.complete((String) j.get("id"), (String) j.get("lease_token"), reply());
    }

    @Test
    void fullPersonaLifecycleAndVersionPinning() throws Exception {
        String v = publish();
        String c = (String) service.start("viewer", persona, null).get("id");
        assertEquals(v, service.session("viewer", c).get("version_id"));
        String second = candidate();
        service.review("owner", persona, second, true);
        assertEquals(v, service.session("viewer", c).get("version_id"));
        service.rollback("owner", persona, v);
        assertEquals(v, service.detail("viewer", persona).get("published_id"));
        mvc.perform(get("/api/personas/" + persona + "/export").header("Authorization", "Bearer demo-viewer"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("# 小溪")));
    }

    @Test
    void previewAndPublishingPermissions() {
        String v = candidate();
        assertThrows(ResponseStatusException.class, () -> service.start("viewer", persona, v));
        assertThrows(ResponseStatusException.class, () -> service.review("editor", persona, v, true));
        assertDoesNotThrow(() -> service.start("editor", persona, v));
        assertTrue(service.list("viewer", project).isEmpty());
    }

    @Test
    void staleCandidateCannotOverwritePublication() {
        String a = candidate(), b = candidate();
        service.review("owner", persona, a, true);
        assertThrows(ResponseStatusException.class, () -> service.review("owner", persona, b, true));
        assertEquals(a, service.detail("owner", persona).get("published_id"));
    }

    @Test
    void correctionCreatesImmutableCandidate() {
        String v = publish();
        var next = service.revise("editor", persona, v,
                Map.of("persona_markdown", "# 小溪\n冲突时先解释。", "correction", "不沉默"));
        assertNotEquals(v, next.get("version_id"));
        var old = db.queryForObject("SELECT content FROM persona_version WHERE id=?", String.class, v);
        assertFalse(old.contains("不沉默"));
    }

    @Test
    void currentMessageIsSeparateFromPreviousConversationHistory() {
        publish();
        String conversation = (String) service.start("viewer", persona, null).get("id");
        chat("viewer", conversation);
        var first = service.claim();
        var firstPayload = (Map<?, ?>) first.get("payload");
        assertTrue(((List<?>) firstPayload.get("history")).isEmpty());
        String previousInput = (String) firstPayload.get("message");
        service.complete((String) first.get("id"), (String) first.get("lease_token"), reply());
        service.chat("viewer", conversation, "这是新的输入，只在本轮出现一次");
        var next = service.claim();
        var payload = (Map<?, ?>) next.get("payload");
        var history = (List<Map<String, Object>>) payload.get("history");
        assertEquals("这是新的输入，只在本轮出现一次", payload.get("message"));
        assertEquals(2, history.size());
        assertTrue(history.stream().anyMatch(message -> previousInput.equals(message.get("body"))));
        assertTrue(history.stream().noneMatch(message -> payload.get("message").equals(message.get("body"))));
    }

    @Test
    void privacyEvenOwnerCannotReadViewerChat() {
        publish();
        String c = (String) service.start("viewer", persona, null).get("id"), j = chat("viewer", c);
        assertThrows(ResponseStatusException.class, () -> service.session("owner", c));
        assertThrows(ResponseStatusException.class, () -> service.job("owner", j));
        assertThrows(ResponseStatusException.class, () -> service.chat("editor", c, "你好"));
        assertTrue(service.conversations("owner", persona).isEmpty());
        assertTrue(service.jobs("owner", persona).stream().noneMatch(x -> j.equals(x.get("id"))));
    }

    @Test
    void busyConversationAndCancellationRejectLateWrite() {
        publish();
        String c = (String) service.start("viewer", persona, null).get("id"), j = chat("viewer", c);
        var lease = service.claim();
        assertThrows(ResponseStatusException.class, () -> chat("viewer", c));
        service.cancel("viewer", j);
        assertThrows(ResponseStatusException.class,
                () -> service.complete(j, (String) lease.get("lease_token"), reply()));
        assertEquals(1, ((List<?>) service.session("viewer", c).get("messages")).size());
    }

    @Test
    void confirmedMemoryOnlyAndUserIsolation() {
        publish();
        String c = (String) service.start("viewer", persona, null).get("id");
        chat("viewer", c);
        completeReply();
        var m = service.memories("viewer", persona).get(0);
        assertEquals("PENDING", m.get("status"));
        chat("viewer", c);
        var first = service.claim();
        assertTrue(((List<?>) ((Map<?, ?>) first.get("payload")).get("memories")).isEmpty());
        service.cancel("viewer", (String) first.get("id"));
        service.memory("viewer", persona, (String) m.get("id"), Map.of("status", "CONFIRMED"));
        chat("viewer", c);
        var second = service.claim();
        assertEquals(1, ((List<?>) ((Map<?, ?>) second.get("payload")).get("memories")).size());
        assertTrue(service.memories("owner", persona).isEmpty());
        assertThrows(ResponseStatusException.class, () -> service.forget("owner", persona, (String) m.get("id")));
    }

    @Test
    void deletedMemoryExcludedEvenFromQueuedJob() {
        publish();
        String c = (String) service.start("viewer", persona, null).get("id");
        chat("viewer", c);
        completeReply();
        var m = service.memories("viewer", persona).get(0);
        service.memory("viewer", persona, (String) m.get("id"), Map.of("status", "CONFIRMED"));
        String old = chat("viewer", c);
        var stale = service.claim();
        service.forget("viewer", persona, (String) m.get("id"));
        assertEquals("CANCELLED", service.job("viewer", old).get("status"));
        assertThrows(ResponseStatusException.class,
                () -> service.complete(old, (String) stale.get("lease_token"), reply()));
        chat("viewer", c);
        var j = service.claim();
        assertTrue(((List<?>) ((Map<?, ?>) j.get("payload")).get("memories")).isEmpty());
    }

    @Test
    void disabledMemoryPreventsCreationAndUse() {
        publish();
        String c = (String) service.start("viewer", persona, null).get("id");
        service.memoryEnabled("viewer", c, false);
        chat("viewer", c);
        var j = service.claim();
        assertEquals(false, ((Map<?, ?>) j.get("payload")).get("memory_enabled"));
        service.complete((String) j.get("id"), (String) j.get("lease_token"), reply());
        assertTrue(service.memories("viewer", persona).isEmpty());
    }

    @Test
    void invalidMemoryOrEmotionRollsBackEntireReply() {
        publish();
        String c = (String) service.start("viewer", persona, null).get("id"), id = chat("viewer", c);
        var j = service.claim();
        var out = new LinkedHashMap<>(reply());
        out.put("memory_candidates", List.of(Map.of("body", "编造", "quote", "不存在于输入的内容")));
        assertThrows(ResponseStatusException.class, () -> service.complete(id, (String) j.get("lease_token"), out));
        assertEquals(1, ((List<?>) service.session("viewer", c).get("messages")).size());
        assertTrue(service.memories("viewer", persona).isEmpty());
        out.put("emotion", Map.of("label", "happy", "intensity", 2, "reason", "无效强度"));
        assertThrows(ResponseStatusException.class, () -> service.complete(id, (String) j.get("lease_token"), out));
    }

    @Test
    void deletionStopsRunningAndRemovesMessagesAndMemories() {
        publish();
        String c = (String) service.start("viewer", persona, null).get("id");
        chat("viewer", c);
        completeReply();
        String j = chat("viewer", c);
        var lease = service.claim();
        service.deleteConversation("viewer", c);
        assertTrue(service.memories("viewer", persona).isEmpty());
        assertTrue(service.conversations("viewer", persona).isEmpty());
        assertThrows(ResponseStatusException.class,
                () -> service.complete(j, (String) lease.get("lease_token"), reply()));
    }

    @Test
    void evidenceAndCrossProjectMaterialValidation() {
        String other = (String) base.createProject("owner", "其他空间").get("id");
        String foreign = (String) base.upload("owner", other, "a.md", "授权原文描述").get("id");
        assertThrows(ResponseStatusException.class, () -> service.generate("owner", persona, List.of(foreign)));
        String m = (String) base.upload("owner", project, "a.md", "他习惯先听对方说完，再解释自己的想法。").get("id");
        service.generate("owner", persona, List.of(m));
        var j = service.claim();
        var bad = new LinkedHashMap<>(generation());
        bad.put("evidence", List.of(Map.of("material_id", m, "quote", "这是一段编造引用")));
        assertThrows(ResponseStatusException.class,
                () -> service.complete((String) j.get("id"), (String) j.get("lease_token"), bad));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM persona_version", Integer.class));
    }

    @Test
    void leaseRecoveryAndDuplicateCompletion() {
        service.generate("owner", persona, List.of());
        var first = service.claim();
        db.update("UPDATE persona_job SET lease_until=0 WHERE id=?", first.get("id"));
        var second = service.claim();
        assertNotEquals(first.get("lease_token"), second.get("lease_token"));
        assertThrows(ResponseStatusException.class,
                () -> service.complete((String) first.get("id"), (String) first.get("lease_token"), generation()));
        var result = service.complete((String) second.get("id"), (String) second.get("lease_token"), generation());
        assertEquals(result,
                service.complete((String) second.get("id"), (String) second.get("lease_token"), generation()));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM persona_version", Integer.class));
    }

    @Test
    void imageAndAuthorizationValidation() {
        assertThrows(ResponseStatusException.class,
                () -> service.setAvatar("owner", persona, "data:image/svg+xml;base64,PHN2Zz4="));
        assertThrows(ResponseStatusException.class,
                () -> service.setAvatar("owner", persona, "data:image/png;base64,aGVsbG8="));
        byte[] oversized = new byte[1048577];
        oversized[0] = (byte) 137;
        oversized[1] = 80;
        oversized[2] = 78;
        oversized[3] = 71;
        assertThrows(ResponseStatusException.class, () -> service.setAvatar("owner", persona,
                "data:image/png;base64," + Base64.getEncoder().encodeToString(oversized)));
        var d = definition();
        d.put("origin", "authorized");
        d.put("consent", false);
        assertThrows(ResponseStatusException.class, () -> service.create("owner", project, d));
    }

    @Test
    void partialRepliesArePrivateLeaseProtectedAndCleared() {
        publish();
        String c = (String) service.start("viewer", persona, null).get("id"), j = chat("viewer", c);
        var lease = service.claim();
        assertThrows(ResponseStatusException.class, () -> service.delta(j, "wrong", "你好"));
        service.delta(j, (String) lease.get("lease_token"), "我在听");
        assertEquals("我在听", service.job("viewer", j).get("partial_reply"));
        assertThrows(ResponseStatusException.class, () -> service.job("owner", j));
        service.cancel("viewer", j);
        assertEquals("", service.job("viewer", j).get("partial_reply"));
        assertThrows(ResponseStatusException.class, () -> service.delta(j, (String) lease.get("lease_token"), "迟到的内容"));
    }

    @Test
    void viewerCannotSeeUnpublishedDefinition() {
        publish();
        var d = definition();
        d.put("background", "未发布的私人草稿");
        service.update("owner", persona, d);
        assertFalse(service.detail("viewer", persona).get("definition").toString().contains("未发布的私人草稿"));
    }

    @Test
    void mediaJobsStayPrivateAndPayloadClears() {
        publish();
        var j = service.media("viewer", persona, Map.of("operation", "ASR", "audio", "data:audio/wav;base64,YWJjZA=="));
        assertThrows(ResponseStatusException.class, () -> service.job("owner", (String) j.get("id")));
        assertTrue(service.jobs("owner", persona).stream().noneMatch(x -> j.get("id").equals(x.get("id"))));
        var lease = service.claim();
        service.complete((String) j.get("id"), (String) lease.get("lease_token"), Map.of("text", "识别文本"));
        assertEquals("{}", db.queryForObject("SELECT payload FROM persona_job WHERE id=?", String.class, j.get("id")));
    }

    @Test
    void imageMessagesUseDedicatedVisionInput() {
        publish();
        String c = (String) service.start("viewer", persona, null).get("id");
        String data = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jXioAAAAASUVORK5CYII=";
        service.chat("viewer", c, "看看图片", data);
        var lease = service.claim();
        assertEquals(data, ((Map<?, ?>) lease.get("payload")).get("image"));
        assertThrows(ResponseStatusException.class, () -> service.session("owner", c));
    }

    @Test
    void archiveIsRecoverableAndChecksActiveJobs() {
        publish();
        service.generate("owner", persona, List.of());
        assertThrows(ResponseStatusException.class, () -> service.archive("owner", persona));
        var j = service.claim();
        service.cancel("owner", (String) j.get("id"));
        service.archive("owner", persona);
        assertTrue(service.list("owner", project).isEmpty());
        assertEquals(1, service.archived("owner", project).size());
        assertThrows(ResponseStatusException.class, () -> service.restore("editor", persona));
        service.restore("owner", persona);
        assertEquals(1, service.list("viewer", project).size());
    }

    @Test
    void duplicateGenerationIsRejected() {
        service.generate("owner", persona, List.of());
        assertThrows(ResponseStatusException.class, () -> service.generate("owner", persona, List.of()));
    }

    @Test
    void loginReportsInvalidTokenWithoutBrowserChallenge() throws Exception {
        mvc.perform(post("/auth/login").header("Authorization", "Bearer invalid-demo-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.error").value("authentication_required"))
            .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(post("/auth/login"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.error").value("authentication_required"));
        mvc.perform(post("/auth/login").header("Authorization", "Bearer demo-owner"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("owner"));
        mvc.perform(get("/api/me").header("Authorization", "Bearer invalid-demo-token"))
            .andExpect(status().isUnauthorized());
    }

    String topic(String workspace, String title, String body) {
        String f = (String) base.createProfile("owner", workspace, title).get("id"), v = UUID.randomUUID().toString();
        db.update(
                "INSERT INTO profile_version(id,profile_id,status,content,work,creator,created_at) VALUES (?,?,'APPROVED',?,?,'owner',?)",
                v, f,
                base.encode(Map.of("claims",
                        List.of(Map.of("id", UUID.randomUUID().toString(), "section", title, "text", body)))),
                body, System.currentTimeMillis());
        db.update("UPDATE profile SET published_id=? WHERE id=?", v, f);
        return f;
    }

    @Test
    void knowledgeIsSharedWithinWorkspaceButConfiguredPerPersona() {
        publish();
        String first = topic(project, "消息排障", "消息延迟先检查队列积压。"), second = topic(project, "客服沟通", "客服沟通先核实需求。");
        String other = (String) service.create("owner", project, definition()).get("id");
        knowledge.configure("owner", persona, List.of(first));
        knowledge.configure("editor", other, List.of(second));
        assertEquals(first, knowledge.retrieve("viewer", persona, "消息延迟").get(0).get("profile_id"));
        assertTrue(knowledge.retrieve("viewer", persona, "客服沟通").isEmpty());
        assertEquals(second, knowledge.retrieve("editor", other, "客服沟通").get(0).get("profile_id"));
        assertThrows(ResponseStatusException.class, () -> knowledge.configure("viewer", persona, List.of(second)));
    }

    @Test
    void knowledgeRejectsForeignDraftAndArchivedTopics() {
        String other = (String) base.createProject("owner", "其他空间").get("id"), foreign = topic(other, "跨空间", "跨空间消息处理");
        assertThrows(ResponseStatusException.class, () -> knowledge.configure("owner", persona, List.of(foreign)));
        String draft = (String) base.createProfile("owner", project, "未发布专题").get("id");
        assertThrows(ResponseStatusException.class, () -> knowledge.configure("owner", persona, List.of(draft)));
        String f = topic(project, "消息排障", "消息延迟先检查积压。");
        knowledge.configure("owner", persona, List.of(f));
        base.deleteProfile("owner", f);
        assertTrue(knowledge.retrieve("owner", persona, "消息延迟").isEmpty());
        assertThrows(ResponseStatusException.class, () -> knowledge.configure("owner", persona, List.of(f)));
    }

    @Test
    void personaReplyUsesFrozenKnowledgeAndStoresVerifiedCitations() {
        publish();
        String f = topic(project, "消息排障", "消息延迟先检查队列积压。");
        knowledge.configure("owner", persona, List.of(f));
        String c = (String) service.start("viewer", persona, null).get("id");
        service.chat("viewer", c, "消息延迟怎么排查？");
        var j = service.claim();
        var sources = PlatformFixture.items(((Map<?, ?>) j.get("payload")).get("knowledge"));
        assertEquals(f, sources.get(0).get("profile_id"));
        var out = new LinkedHashMap<>(reply());
        out.put("memory_candidates", List.of());
        out.put("citations", List.of(sources.get(0).get("id")));
        var result = service.complete((String) j.get("id"), (String) j.get("lease_token"), out);
        assertEquals(sources, result.get("citations"));
        var messages = PlatformFixture.items(service.session("viewer", c).get("messages"));
        assertEquals(1, ((List<?>) messages.get(1).get("citations")).size());
    }

    @Test
    void revokedKnowledgeStopsLateReplyAndForgedSourcesRollback() {
        publish();
        String f = topic(project, "消息排障", "消息延迟检查队列。");
        knowledge.configure("owner", persona, List.of(f));
        String c = (String) service.start("viewer", persona, null).get("id");
        service.chat("viewer", c, "消息延迟？");
        var j = service.claim();
        var out = new LinkedHashMap<>(reply());
        out.put("memory_candidates", List.of());
        out.put("citations", List.of("forged-source"));
        assertThrows(ResponseStatusException.class,
                () -> service.complete((String) j.get("id"), (String) j.get("lease_token"), out));
        assertEquals(1, ((List<?>) service.session("viewer", c).get("messages")).size());
        knowledge.configure("owner", persona, List.of());
        assertEquals("CANCELLED", service.job("viewer", (String) j.get("id")).get("status"));
        assertThrows(ResponseStatusException.class,
                () -> service.complete((String) j.get("id"), (String) j.get("lease_token"), out));
    }

    @Test
    void archivedKnowledgeCannotCompleteAsCurrentSource() {
        publish();
        String f = topic(project, "消息排障", "消息延迟检查队列。");
        knowledge.configure("owner", persona, List.of(f));
        String c = (String) service.start("viewer", persona, null).get("id");
        service.chat("viewer", c, "消息延迟？");
        var j = service.claim();
        base.deleteProfile("owner", f);
        var out = new LinkedHashMap<>(reply());
        out.put("memory_candidates", List.of());
        assertThrows(ResponseStatusException.class,
                () -> service.complete((String) j.get("id"), (String) j.get("lease_token"), out));
    }

    @Test
    void authRoutesAndPrivateErrorShape() throws Exception {
        mvc.perform(get("/api/personas/" + persona)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/personas/" + persona).header("Authorization", "Bearer demo-viewer"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/internal/persona-jobs/claim").header("Authorization", "Bearer demo-owner"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void independentPersonalityMultipleMaterialsAndFrozenBinding() {
        String library = (String) service.createPersonality("owner", project, definition()).get("id");
        assertEquals(1, service.list("owner", project).size());
        assertEquals(1, service.personalities("owner", project).size());
        String a = (String) base.upload("owner", project, "one.md", "表达自然，先听完再回应。").get("id"),
                b = (String) base.upload("owner", project, "two.md", "发生分歧时先澄清，不沉默。").get("id");
        service.generate("owner", library, List.of(a, b));
        var job = service.claim();
        assertEquals(2, ((List<?>) ((Map<?, ?>) job.get("payload")).get("sources")).size());
        String version = (String) service
            .complete((String) job.get("id"), (String) job.get("lease_token"), generation())
            .get("version_id");
        assertThrows(ResponseStatusException.class, () -> service.bindPersonality("owner", persona, library, version));
        service.review("owner", library, version, true);
        String old = publish();
        String conversation = (String) service.start("owner", persona, null).get("id");
        var bound = service.bindPersonality("owner", persona, library, version);
        assertNotEquals(old, bound.get("published_id"));
        assertEquals(old, service.session("owner", conversation).get("version_id"));
        assertEquals(version,
                base.one("persona_version", (String) bound.get("published_id")).get("source_personality_version_id"));
        String second = (String) service.create("owner", project, definition()).get("id");
        service.bindPersonality("owner", second, library, version);
        assertEquals(2, ((List<?>) service.detail("owner", library).get("bindings")).size());
        service.generate("owner", library, List.of());
        job = service.claim();
        String next = (String) service.complete((String) job.get("id"), (String) job.get("lease_token"), generation())
            .get("version_id");
        service.review("owner", library, next, true);
        assertEquals(bound.get("published_id"), service.detail("owner", persona).get("published_id"));
    }

    @Test
    void personalityBindingEnforcesWorkspaceRoleAndAssetType() {
        String library = (String) service.createPersonality("owner", project, definition()).get("id");
        service.generate("owner", library, List.of());
        var job = service.claim();
        String version = (String) service
            .complete((String) job.get("id"), (String) job.get("lease_token"), generation())
            .get("version_id");
        service.review("owner", library, version, true);
        assertThrows(ResponseStatusException.class, () -> service.bindPersonality("editor", persona, library, version));
        assertThrows(ResponseStatusException.class, () -> service.bindPersonality("viewer", persona, library, version));
        assertThrows(ResponseStatusException.class, () -> service.bindPersonality("owner", library, library, version));
        String other = (String) base.createProject("owner", "另一空间").get("id"),
                target = (String) service.create("owner", other, definition()).get("id");
        assertThrows(ResponseStatusException.class, () -> service.bindPersonality("owner", target, library, version));
        mvcPerformBinding(persona, library, version);
    }

    void mvcPerformBinding(String target, String library, String version) {
        try {
            mvc.perform(put("/api/personas/" + target + "/personality").header("Authorization", "Bearer demo-owner")
                .contentType("application/json")
                .content("{\"personality_id\":\"" + library + "\",\"version_id\":\"" + version + "\"}"))
                .andExpect(status().isOk());
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void recentChatRunsBeforeBackgroundButAgedBackgroundEventuallyRuns() {
        publish();
        String c = (String) service.start("viewer", persona, null).get("id");
        var background = service.generate("owner", persona, List.of());
        var chat = service.chat("viewer", c, "合成即时聊天");
        var first = service.claim();
        assertEquals(chat.get("id"), first.get("id"));
        assertEquals("CHAT", first.get("kind"));
        service.cancel("viewer", (String) chat.get("id"));
        db.update("UPDATE persona_job SET created_at=? WHERE id=?", System.currentTimeMillis() - 120000L, background.get("id"));
        var nextChat = service.chat("viewer", c, "合成下一轮聊天");
        var second = service.claim();
        assertEquals(background.get("id"), second.get("id"), "aged background cannot starve behind a continuous chat queue");
        service.complete((String) second.get("id"), (String) second.get("lease_token"), generation());
        assertEquals(nextChat.get("id"), service.claim().get("id"));
    }

    @Test
    void deterministicOutputFailureStopsImmediatelyButExplicitRetryStillWorks() {
        publish(); String c = (String) service.start("viewer", persona, null).get("id");
        var queued = service.chat("viewer", c, "合成格式失败"); var lease = service.claim();
        service.fail((String) lease.get("id"), (String) lease.get("lease_token"), "INVALID_MODEL_OUTPUT");
        assertEquals("FAILED", service.job("viewer", (String) queued.get("id")).get("status"));
        assertEquals(1, service.job("viewer", (String) queued.get("id")).get("attempt"));
        assertNull(service.claim());
        service.retry("viewer", (String) queued.get("id"));
        assertEquals(queued.get("id"), service.claim().get("id"));
    }

    @Test
    void gracefulReleaseIsIdempotentRefundsOnlyCurrentAttemptAndCannotReviveCancellation() throws Exception {
        publish(); String c = (String) service.start("viewer", persona, null).get("id");
        var queued = service.chat("viewer", c, "合成退出恢复"); var first = service.claim();
        String path = "/internal/persona-jobs/" + queued.get("id") + "/release";
        String body = "{\"lease_token\":\"" + first.get("lease_token") + "\"}";
        mvc.perform(post(path).header("Authorization", "Bearer demo-viewer").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        for (int n = 0; n < 2; n++)
            mvc.perform(post(path).header("Authorization", "Bearer test-worker-token-32-characters-long").contentType("application/json").content(body)).andExpect(status().isOk());
        assertEquals("QUEUED", service.job("viewer", (String) queued.get("id")).get("status"));
        assertEquals(0, service.job("viewer", (String) queued.get("id")).get("attempt"));
        var next = service.claim(); assertNotEquals(first.get("lease_token"), next.get("lease_token"));
        assertThrows(ResponseStatusException.class, () -> service.complete((String) queued.get("id"), (String) first.get("lease_token"), reply()));
        service.cancel("viewer", (String) queued.get("id"));
        mvc.perform(post(path).header("Authorization", "Bearer test-worker-token-32-characters-long").contentType("application/json")
                .content("{\"lease_token\":\"" + next.get("lease_token") + "\"}")).andExpect(status().isConflict());
    }

    @Test
    void expiredChatLeaseDropsOldPartialAndFinalReplyRemainsUnique() {
        publish(); String c = (String) service.start("viewer", persona, null).get("id");
        String jobId = chat("viewer", c); var first = service.claim();
        service.delta(jobId, (String) first.get("lease_token"), "旧的中断片段");
        db.update("UPDATE persona_job SET lease_until=0 WHERE id=?", jobId);
        assertThrows(ResponseStatusException.class, () -> service.complete(jobId, (String) first.get("lease_token"), reply()));
        var second = service.claim(); assertNotEquals(first.get("lease_token"), second.get("lease_token"));
        assertEquals("", service.job("viewer", jobId).get("partial_reply"));
        var saved = service.complete(jobId, (String) second.get("lease_token"), reply());
        db.update("UPDATE persona_job SET lease_until=0 WHERE id=?", jobId);
        assertEquals(saved, service.complete(jobId, (String) second.get("lease_token"), Map.of("answer", "不能替换已保存结果")));
        assertEquals(2, ((List<?>) service.session("viewer", c).get("messages")).size());
        assertEquals("calm", ((Map<?, ?>) service.session("viewer", c).get("emotion")).get("previous"));
        assertEquals(1, service.memories("viewer", persona).size());
    }

    @Test
    void cancellationBeforeLeaseRecoveryNeverRevivesKilledChat() {
        publish(); String c = (String) service.start("viewer", persona, null).get("id");
        String jobId = chat("viewer", c); var first = service.claim();
        db.update("UPDATE persona_job SET lease_until=0 WHERE id=?", jobId);
        service.cancel("viewer", jobId);
        assertNull(service.claim());
        assertThrows(ResponseStatusException.class, () -> service.complete(jobId, (String) first.get("lease_token"), reply()));
        assertEquals("CANCELLED", service.job("viewer", jobId).get("status"));
        assertEquals(1, ((List<?>) service.session("viewer", c).get("messages")).size());
    }

}
