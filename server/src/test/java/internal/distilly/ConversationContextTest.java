package internal.distilly;

import internal.distilly.common.json.JsonCodec;
import internal.distilly.config.PlatformProperties;
import internal.distilly.modules.conversation.domain.ExperienceRecall;
import internal.distilly.modules.conversation.service.ContextAssembler;
import internal.distilly.modules.memory.service.MemoryRetrieval;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static internal.distilly.common.validation.InputChecks.items;

/** Synthetic fixtures: verifies source/budget invariants, not real-person imitation quality. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:conversation-context;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "platform.demo=true", "platform.worker-token=context-test-worker-token-32-characters"})
class ConversationContextTest {
    @Autowired PersonaFixture personas;
    @Autowired PlatformFixture platform;
    @Autowired MemoryRetrieval recall;
    @Autowired ContextAssembler assembler;
    @Autowired PlatformProperties properties;
    @Autowired JdbcTemplate db;
    @Autowired JsonCodec json;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    String project, persona, version, conversation;

    Map<String, Object> definition(String name) {
        return Map.of("name", name, "family", "relationship", "origin", "original", "expression", "短句，先倾听再追问",
                "personality", "温和但有独立判断", "boundaries", "不猜测真实经历", "background", "原创设定：在青岛学习摄影。", "consent", false);
    }

    String publish(String target, String markdown) {
        personas.generate("owner", target, List.of());
        var job = personas.claim();
        String id = (String) personas.complete((String) job.get("id"), (String) job.get("lease_token"),
                Map.of("persona_markdown", markdown, "analysis", Map.of("inferences", List.of("不能当成经历")), "evidence", List.of())).get("version_id");
        personas.review("owner", target, id, true);
        return id;
    }

    @BeforeEach
    void setup() {
        properties.setContextBudgetUnits(32768);
        properties.setReplyOutputTokens(2048);
        for (String table : List.of("persona_knowledge", "persona_job", "persona_memory", "persona_message", "persona_conversation",
                "persona_version", "persona", "job_archive", "job_progress", "profile_archive", "audit", "job", "profile_version", "profile", "material", "member", "project"))
            db.update("DELETE FROM " + table);
        project = (String) platform.createProject("owner", "合成文字验收").get("id");
        platform.member("owner", project, "viewer", "VIEWER");
        persona = (String) personas.create("owner", project, definition("小溪")).get("id");
        version = publish(persona, "# 小溪\n短句，先倾听再追问，未知经历明确说明不足。");
        conversation = (String) personas.start("viewer", persona, null).get("id");
    }

    String memory(String c, String p, String user, String body, String status, long time) {
        String source = UUID.randomUUID().toString(), id = UUID.randomUUID().toString();
        db.update("INSERT INTO persona_message(id,conversation_id,role,body,created_at) VALUES (?,?,'user',?,?)", source, c, body, time);
        db.update("INSERT INTO persona_memory(id,persona_id,user_id,body,source_id,status,created_at) VALUES (?,?,?,?,?,?,?)",
                id, p, user, body, source, status, time);
        return id;
    }

    Map<String, Object> claim(String query) {
        personas.chat("viewer", conversation, query);
        return personas.claim();
    }

    Map<String, Object> reply(String memory, String experience) {
        return Map.of("answer", "合成协议回应", "emotion", Map.of("label", "warm", "intensity", 0.5, "reason", "合成情境"),
                "memory_candidates", List.of(), "memory_citations", memory.isEmpty() ? List.of() : List.of(memory),
                "experience_citations", experience.isEmpty() ? List.of() : List.of(experience));
    }

    void assertUnavailableChatCleared(Object job) {
        var row = db.queryForMap("SELECT status,payload,result,lease_token,lease_until,partial_reply,knowledge_snapshot FROM persona_job WHERE id=?", job);
        assertEquals("CANCELLED", row.get("status"));
        assertTrue(json.decode(row.get("payload")).isEmpty());
        assertTrue(row.get("result") == null && row.get("lease_token") == null && row.get("lease_until") == null);
        assertTrue("".equals(row.get("partial_reply")) && "[]".equals(row.get("knowledge_snapshot")));
    }

    @Test
    void oldRelevantMemoryBeatsFortyNewUnrelatedItemsAndScopeIsStrict() {
        String expected = memory(conversation, persona, "viewer", "我们共同确认：雨天在青岛海边拍照。", "CONFIRMED", 1);
        for (int i = 0; i < 45; i++) memory(conversation, persona, "viewer", "用户选择咖啡店座位编号" + i, "CONFIRMED", 10 + i);
        memory(conversation, persona, "viewer", "青岛海边秘密未确认", "PENDING", 100);
        memory(conversation, persona, "viewer", "青岛海边已拒绝", "REJECTED", 101);
        String ownerConversation = (String) personas.start("owner", persona, null).get("id");
        memory(ownerConversation, persona, "owner", "青岛海边其他用户私密行程", "CONFIRMED", 102);
        String other = (String) personas.create("owner", project, definition("另一人物")).get("id");
        publish(other, "另一人物");
        String otherConversation = (String) personas.start("viewer", other, null).get("id");
        memory(otherConversation, other, "viewer", "青岛海边另一人物经历", "CONFIRMED", 103);
        assertEquals(List.of(expected), recall.retrieve("viewer", persona, "青岛海边拍照").stream().map(x -> x.get("id")).toList());
        assertTrue(recall.retrieve("viewer", persona, "火星旅行").isEmpty());
        var job = claim("青岛海边拍照时我们确认过什么？");
        var payload = json.decode(json.encode(job.get("payload")));
        assertEquals(expected, items(payload.get("memories")).get(0).get("id"));
        assertEquals("confirmed_interaction", items(payload.get("memories")).get(0).get("origin"));
        personas.complete((String) job.get("id"), (String) job.get("lease_token"), reply(expected, ""));
    }

    @Test
    void deletedCorrectedAndDisabledRecallNeverReuseOldSelection() {
        String id = memory(conversation, persona, "viewer", "我们在青岛海边拍照", "CONFIRMED", 1);
        var stale = claim("青岛海边拍照");
        personas.memory("viewer", persona, id, Map.of("status", "CONFIRMED", "body", "我们在北京公园散步"));
        assertThrows(ResponseStatusException.class, () -> personas.complete((String) stale.get("id"), (String) stale.get("lease_token"), reply(id, "")));
        assertTrue(recall.retrieve("viewer", persona, "青岛海边拍照").isEmpty());
        assertEquals(id, recall.retrieve("viewer", persona, "北京公园散步").get(0).get("id"));
        personas.forget("viewer", persona, id);
        assertTrue(recall.retrieve("viewer", persona, "北京公园散步").isEmpty());
        memory(conversation, persona, "viewer", "我们在北京公园散步", "CONFIRMED", 2);
        personas.memoryEnabled("viewer", conversation, false);
        assertTrue(items(json.decode(json.encode(claim("北京公园散步").get("payload"))).get("memories")).isEmpty());
    }

    @Test
    void deletingMemorySourceConversationRejectsHeldReplyInAnotherConversation() {
        String memoryId = memory(conversation, persona, "viewer", "我们在青岛海边拍照", "CONFIRMED", 1);
        String other = personas.start("viewer", persona, null).get("id").toString();
        var job = personas.chat("viewer", other, "青岛海边拍照");
        var lease = personas.claim();
        assertEquals(job.get("id"), lease.get("id"));
        assertEquals(1, items(((Map<?, ?>) lease.get("payload")).get("memories")).size());
        personas.deleteConversation("viewer", conversation);
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM persona_memory WHERE id=?", Integer.class, memoryId));
        int status = 200;
        try {
            personas.complete(job.get("id").toString(), lease.get("lease_token").toString(), reply(memoryId, ""));
        } catch (ResponseStatusException error) {
            status = error.getStatusCode().value();
        }
        int assistants = db.queryForObject("SELECT COUNT(*) FROM persona_message WHERE conversation_id=? AND role='assistant'",
                Integer.class, other);
        assertEquals("409/0", status + "/" + assistants, "late completion status/assistant count only");
        assertEquals("CANCELLED", personas.job("viewer", job.get("id").toString()).get("status"));
        var row = db.queryForMap("SELECT payload,partial_reply,lease_token,knowledge_snapshot FROM persona_job WHERE id=?", job.get("id"));
        assertEquals("{}", row.get("payload"));
        assertEquals("", row.get("partial_reply"));
        assertNull(row.get("lease_token"));
        assertEquals("[]", row.get("knowledge_snapshot"));
        var next = personas.chat("viewer", other, "青岛海边拍照");
        var fresh = personas.claim();
        assertEquals(next.get("id"), fresh.get("id"));
        assertTrue(items(((Map<?, ?>) fresh.get("payload")).get("memories")).isEmpty());
        personas.complete(next.get("id").toString(), fresh.get("lease_token").toString(), reply("", ""));
    }

    @Test
    void chatCreatedAfterCorrectionCancellationCannotDeliverPreCommitMemoryBody() throws Exception {
        String original = "合成约定：青岛海边拍照安排周五。";
        String corrected = "合成约定：青岛海边拍照改为周六。";
        String id = memory(conversation, persona, "viewer", original, "CONFIRMED", 1);
        String target = personas.start("viewer", persona, null).get("id").toString();
        var updated = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var correction = executor.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactions)
                    .executeWithoutResult(transaction -> {
                        personas.memory("viewer", persona, id, Map.of("status", "CONFIRMED", "body", corrected));
                        updated.countDown(); // The cancellation scan ran; outer commit is deliberately held.
                        try {
                            if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test_release_timeout");
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("test_interrupted");
                        }
                    }));
            assertTrue(updated.await(10, java.util.concurrent.TimeUnit.SECONDS));
            var job = personas.chat("viewer", target, "青岛海边拍照");
            var claiming = executor.submit(() -> personas.claim());
            Map<String, Object> lease = null;
            try { lease = claiming.get(250, java.util.concurrent.TimeUnit.MILLISECONDS); }
            catch (java.util.concurrent.TimeoutException waitingForCommit) { }
            boolean finishedBeforeCommit = claiming.isDone();
            boolean issuedBeforeCommit = finishedBeforeCommit && lease != null;
            release.countDown();
            correction.get(10, java.util.concurrent.TimeUnit.SECONDS);
            if (!finishedBeforeCommit) lease = claiming.get(10, java.util.concurrent.TimeUnit.SECONDS);
            if (lease == null) lease = personas.claim(); // A skipped busy scope is retried by polling, not resubmitting.
            assertNotNull(lease);
            assertEquals(job.get("id"), lease.get("id"));
            var selected = items(((Map<?, ?>) lease.get("payload")).get("memories"));
            assertEquals(1, selected.size());
            boolean snapshotCurrent = corrected.equals(selected.get(0).get("body"));
            var output = new LinkedHashMap<>(reply(id, ""));
            output.put("answer", selected.get(0).get("body"));
            var answer = personas.complete(job.get("id").toString(), lease.get("lease_token").toString(), output);
            boolean replyCurrent = corrected.equals(answer.get("answer"));
            java.nio.file.Files.writeString(java.nio.file.Path.of("target/memory-claim-correction-race.json"),
                    json.encode(Map.of("claim_finished_before_commit", finishedBeforeCommit,
                            "lease_issued_before_commit", issuedBeforeCommit,
                            "snapshot_current_body", snapshotCurrent, "reply_current_body", replyCurrent,
                            "assistant_messages", items(personas.session("viewer", target).get("messages")).size() - 1)));
            assertEquals("true/true", snapshotCurrent + "/" + replyCurrent, "snapshot/reply current-body flags only");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void revokedQueuedCreatorCannotPoisonAnotherUsersClaim() throws Exception {
        var revoked = personas.chat("viewer", conversation, "合成撤权前排队消息");
        db.update("UPDATE persona_job SET created_at=1 WHERE id=?", revoked.get("id"));
        String healthyConversation = personas.start("owner", persona, null).get("id").toString();
        var healthy = personas.chat("owner", healthyConversation, "合成仍获授权消息");
        // Simulate membership revocation only in this dedicated test database.
        db.update("DELETE FROM member WHERE project_id=? AND user_id=?", project, "viewer");
        int claimStatus = 200;
        Map<String, Object> claimed = null;
        try { claimed = personas.claim(); }
        catch (ResponseStatusException error) { claimStatus = error.getStatusCode().value(); }
        boolean healthyClaimed = claimed != null && healthy.get("id").equals(claimed.get("id"));
        String revokedStatus = db.queryForObject("SELECT status FROM persona_job WHERE id=?", String.class, revoked.get("id"));
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/revoked-claim-evaluation.json"),
                json.encode(Map.of("claim_status", claimStatus, "healthy_task_claimed", healthyClaimed,
                        "revoked_task_status", revokedStatus, "revoked_attempt",
                        db.queryForObject("SELECT attempt FROM persona_job WHERE id=?", Integer.class, revoked.get("id")))));
        assertEquals("200/true/CANCELLED", claimStatus + "/" + healthyClaimed + "/" + revokedStatus,
                "claim status/healthy task/revoked task state only");
        assertUnavailableChatCleared(revoked.get("id"));
        assertEquals(0, db.queryForObject("SELECT attempt FROM persona_job WHERE id=?", Integer.class, revoked.get("id")));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM persona_message WHERE conversation_id=? AND role='assistant'", Integer.class, conversation));
        personas.complete(claimed.get("id").toString(), claimed.get("lease_token").toString(), reply("", ""));
        assertEquals(2, items(personas.session("owner", healthyConversation).get("messages")).size());
    }

    @Test
    void expiredRevokedLeaseIsCancelledAndOldCallbacksStayInvalidAfterRegrant() {
        String id = memory(conversation, persona, "viewer", "青岛海边拍照", "CONFIRMED", 1);
        var job = personas.chat("viewer", conversation, "青岛海边拍照");
        var old = personas.claim();
        assertEquals(job.get("id"), old.get("id"));
        assertEquals(1, items(((Map<?, ?>) old.get("payload")).get("memories")).size());
        personas.delta(job.get("id").toString(), old.get("lease_token").toString(), "合成旧片段");
        db.update("UPDATE persona_job SET lease_until=0 WHERE id=?", job.get("id"));
        db.update("DELETE FROM member WHERE project_id=? AND user_id=?", project, "viewer");
        assertNull(personas.claim());
        assertUnavailableChatCleared(job.get("id"));
        assertEquals(1, db.queryForObject("SELECT attempt FROM persona_job WHERE id=?", Integer.class, job.get("id")));
        platform.member("owner", project, "viewer", "VIEWER");
        var error = assertThrows(ResponseStatusException.class,
                () -> personas.complete(job.get("id").toString(), old.get("lease_token").toString(), reply(id, "")));
        assertEquals(409, error.getStatusCode().value());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM persona_message WHERE conversation_id=? AND role='assistant'", Integer.class, conversation));
    }

    @Test
    void restoredMembershipDoesNotReviveCancelledTaskButAllowsExplicitNewTurn() {
        var old = personas.chat("viewer", conversation, "合成撤权前消息");
        db.update("DELETE FROM member WHERE project_id=? AND user_id=?", project, "viewer");
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> personas.job("viewer", old.get("id").toString())).getStatusCode().value());
        assertNull(personas.claim());
        platform.member("owner", project, "viewer", "VIEWER");
        assertNull(personas.claim());
        assertEquals("CANCELLED", personas.job("viewer", old.get("id").toString()).get("status"));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> personas.retry("viewer", old.get("id").toString())).getStatusCode().value());
        var next = personas.chat("viewer", conversation, "合成恢复授权后显式新消息");
        var lease = personas.claim();
        assertEquals(next.get("id"), lease.get("id"));
        assertNotEquals(old.get("id"), lease.get("id"));
        personas.complete(lease.get("id").toString(), lease.get("lease_token").toString(), reply("", ""));
        assertEquals(3, items(personas.session("viewer", conversation).get("messages")).size());
    }

    @Test
    void publishedChatRemainsAuthorizedAfterEditorBecomesViewer() {
        platform.member("owner", project, "editor", "EDITOR");
        String own = personas.start("editor", persona, null).get("id").toString();
        var job = personas.chat("editor", own, "合成已发布人物交流");
        platform.member("owner", project, "editor", "VIEWER");
        var lease = personas.claim();
        assertEquals(job.get("id"), lease.get("id"));
        personas.complete(lease.get("id").toString(), lease.get("lease_token").toString(), reply("", ""));
        assertEquals(2, items(personas.session("editor", own).get("messages")).size());
    }

    @Test
    void unavailablePersonaCandidateDoesNotPoisonAnotherPersonasClaim() {
        String available = personas.create("owner", project, definition("另一合成人物")).get("id").toString();
        publish(available, "# 另一合成人物");
        String healthyConversation = personas.start("owner", available, null).get("id").toString();
        var old = personas.chat("viewer", conversation, "合成不可用人物消息");
        db.update("UPDATE persona_job SET created_at=1 WHERE id=?", old.get("id"));
        // Defensive fixture state: the public archive command rejects active jobs.
        db.update("UPDATE persona SET deleted=TRUE WHERE id=?", persona);
        var healthy = personas.chat("owner", healthyConversation, "合成仍可用人物消息");
        var lease = personas.claim();
        assertEquals(healthy.get("id"), lease.get("id"));
        assertUnavailableChatCleared(old.get("id"));
        personas.complete(lease.get("id").toString(), lease.get("lease_token").toString(), reply("", ""));
    }

    @Test
    void busyMemoryScopeSkipsOldestChatWithoutBlockingAnotherPersona() throws Exception {
        String id = memory(conversation, persona, "viewer", "青岛海边拍照", "CONFIRMED", 1);
        String otherPersona = personas.create("owner", project, definition("独立合成人物")).get("id").toString();
        publish(otherPersona, "# 独立合成人物");
        String other = personas.start("owner", otherPersona, null).get("id").toString();
        var ready = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var mutation = executor.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactions)
                    .executeWithoutResult(transaction -> {
                        personas.memory("viewer", persona, id, Map.of("status", "REJECTED"));
                        ready.countDown();
                        try {
                            if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test_release_timeout");
                        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException("test_interrupted"); }
                    }));
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            var busy = personas.chat("viewer", conversation, "青岛海边拍照");
            db.update("UPDATE persona_job SET created_at=1 WHERE id=?", busy.get("id"));
            var independent = personas.chat("owner", other, "合成独立问题");
            var claimed = personas.claim();
            assertNotNull(claimed);
            assertEquals(independent.get("id"), claimed.get("id"));
            assertEquals("QUEUED", personas.job("viewer", busy.get("id").toString()).get("status"));
            assertEquals(0, db.queryForObject("SELECT attempt FROM persona_job WHERE id=?", Integer.class, busy.get("id")));
            personas.complete(claimed.get("id").toString(), claimed.get("lease_token").toString(), reply("", ""));
            release.countDown(); mutation.get(10, java.util.concurrent.TimeUnit.SECONDS);
            var fresh = personas.claim();
            assertEquals(busy.get("id"), fresh.get("id"));
            assertTrue(items(((Map<?, ?>) fresh.get("payload")).get("memories")).isEmpty());
            personas.complete(fresh.get("id").toString(), fresh.get("lease_token").toString(), reply("", ""));
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test
    void rolledBackCorrectionReleasesScopeAndKeepsOriginalMemoryForNewChat() throws Exception {
        String original = "合成约定：青岛海边拍照安排周五。";
        String id = memory(conversation, persona, "viewer", original, "CONFIRMED", 1);
        var ready = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var mutation = executor.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactions)
                    .executeWithoutResult(transaction -> {
                        personas.memory("viewer", persona, id, Map.of("status", "CONFIRMED", "body", "合成约定：青岛海边拍照改为周六。"));
                        ready.countDown();
                        try {
                            if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test_release_timeout");
                        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException("test_interrupted"); }
                        transaction.setRollbackOnly();
                    }));
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            var queued = personas.chat("viewer", conversation, "青岛海边拍照");
            assertNull(personas.claim());
            assertEquals("QUEUED", personas.job("viewer", queued.get("id").toString()).get("status"));
            release.countDown(); mutation.get(10, java.util.concurrent.TimeUnit.SECONDS);
            var lease = personas.claim();
            assertEquals(queued.get("id"), lease.get("id"));
            assertTrue(original.equals(items(((Map<?, ?>) lease.get("payload")).get("memories")).get(0).get("body")));
            personas.complete(lease.get("id").toString(), lease.get("lease_token").toString(), reply(id, ""));
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test
    void simultaneousClaimsDoNotDuplicateLeasesAndPollingFindsRemainingConversation() throws Exception {
        String other = personas.start("viewer", persona, null).get("id").toString();
        var first = personas.chat("viewer", conversation, "合成第一会话");
        var second = personas.chat("viewer", other, "合成第二会话");
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var leases = new LinkedHashMap<String, Map<String, Object>>();
        try {
            var futures = java.util.stream.IntStream.range(0, 2).mapToObj(i -> executor.submit(() -> {
                ready.countDown(); assertTrue(start.await(10, java.util.concurrent.TimeUnit.SECONDS));
                return personas.claim();
            })).toList();
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)); start.countDown();
            for (var future : futures) {
                var lease = future.get(10, java.util.concurrent.TimeUnit.SECONDS);
                if (lease != null) assertNull(leases.put(lease.get("id").toString(), lease), "one lease per task");
            }
            for (int i = 0; i < 2 && leases.size() < 2; i++) {
                var lease = personas.claim();
                if (lease != null) assertNull(leases.put(lease.get("id").toString(), lease), "poll must not duplicate a lease");
            }
            assertEquals(Set.of(first.get("id"), second.get("id")), leases.keySet());
            for (var lease : leases.values()) {
                assertEquals(1, db.queryForObject("SELECT attempt FROM persona_job WHERE id=?", Integer.class, lease.get("id")));
                personas.complete(lease.get("id").toString(), lease.get("lease_token").toString(), reply("", ""));
            }
            assertEquals(2, items(personas.session("viewer", conversation).get("messages")).size());
            assertEquals(2, items(personas.session("viewer", other).get("messages")).size());
        } finally { start.countDown(); executor.shutdownNow(); }
    }

    @Test
    void sourceDeletionCancelsOnlySameUserAndPersonaAcrossConversations() {
        memory(conversation, persona, "viewer", "青岛海边拍照", "CONFIRMED", 1);
        String secondPersona = personas.create("owner", project, definition("另一个合成人物")).get("id").toString();
        publish(secondPersona, "# 另一个合成人物");
        String ownerSource = personas.start("owner", persona, null).get("id").toString();
        memory(ownerSource, persona, "owner", "青岛海边拍照", "CONFIRMED", 2);
        String independent = personas.start("viewer", secondPersona, null).get("id").toString();
        memory(independent, secondPersona, "viewer", "青岛海边拍照", "CONFIRMED", 3);
        String runningConversation = personas.start("viewer", persona, null).get("id").toString();
        var running = personas.chat("viewer", runningConversation, "青岛海边拍照");
        var held = personas.claim();
        assertEquals(running.get("id"), held.get("id"));
        String queuedConversation = personas.start("viewer", persona, null).get("id").toString();
        var queued = personas.chat("viewer", queuedConversation, "青岛海边拍照");
        var ownerJob = personas.chat("owner", ownerSource, "青岛海边拍照");
        var otherPersonaJob = personas.chat("viewer", independent, "青岛海边拍照");
        personas.deleteConversation("viewer", conversation);
        for (var job : List.of(running, queued))
            assertEquals("CANCELLED", personas.job("viewer", job.get("id").toString()).get("status"));
        assertEquals("QUEUED", personas.job("owner", ownerJob.get("id").toString()).get("status"));
        assertEquals("QUEUED", personas.job("viewer", otherPersonaJob.get("id").toString()).get("status"));
        assertEquals(1, recall.retrieve("owner", persona, "青岛海边拍照").size());
        assertEquals(1, recall.retrieve("viewer", secondPersona, "青岛海边拍照").size());
    }

    @Test
    void unauthorizedSourceDeletionHasNoMemoryOrTaskSideEffect() {
        String id = memory(conversation, persona, "viewer", "青岛海边拍照", "CONFIRMED", 1);
        String other = personas.start("viewer", persona, null).get("id").toString();
        var job = personas.chat("viewer", other, "青岛海边拍照");
        var held = personas.claim();
        var error = assertThrows(ResponseStatusException.class, () -> personas.deleteConversation("owner", conversation));
        assertEquals(403, error.getStatusCode().value());
        assertEquals(1, recall.retrieve("viewer", persona, "青岛海边拍照").size());
        assertEquals("RUNNING", personas.job("viewer", job.get("id").toString()).get("status"));
        personas.complete(job.get("id").toString(), held.get("lease_token").toString(), reply(id, ""));
    }

    @Test
    void deletingConversationWithoutDerivedMemoriesKeepsOtherChatRunning() {
        String source = personas.start("viewer", persona, null).get("id").toString();
        String id = memory(source, persona, "viewer", "青岛海边拍照", "CONFIRMED", 1);
        String other = personas.start("viewer", persona, null).get("id").toString();
        var job = personas.chat("viewer", other, "青岛海边拍照");
        var held = personas.claim();
        personas.deleteConversation("viewer", conversation);
        assertEquals("RUNNING", personas.job("viewer", job.get("id").toString()).get("status"));
        personas.complete(job.get("id").toString(), held.get("lease_token").toString(), reply(id, ""));
    }

    @Test
    void finalMemoryGuardRejectsMissingUnconfirmedOrForeignSourcesEvenWithoutCitations() {
        String id = memory(conversation, persona, "viewer", "青岛海边拍照", "CONFIRMED", 1);
        recall.validateAccess("viewer", persona, List.of(id));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> recall.validateAccess("owner", persona, List.of(id))).getStatusCode().value());
        db.update("UPDATE persona_memory SET status='REJECTED' WHERE id=?", id);
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> recall.validateAccess("viewer", persona, List.of(id))).getStatusCode().value());
        db.update("UPDATE persona_memory SET status='CONFIRMED' WHERE id=?", id);
        var held = claim("青岛海边拍照");
        // Simulate a context missed by proactive cancellation; final validation is independent.
        db.update("DELETE FROM persona_memory WHERE id=?", id);
        var error = assertThrows(ResponseStatusException.class, () -> personas.complete(held.get("id").toString(),
                held.get("lease_token").toString(), reply("", "")));
        assertEquals(409, error.getStatusCode().value());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM persona_message WHERE conversation_id=? AND role='assistant'",
                Integer.class, conversation));
    }

    @Test
    void concurrentSourceDeletesFinishWithoutRevivingOtherConversations() throws Exception {
        memory(conversation, persona, "viewer", "青岛海边拍照", "CONFIRMED", 1);
        String second = personas.start("viewer", persona, null).get("id").toString();
        memory(second, persona, "viewer", "青岛海边拍照", "CONFIRMED", 2);
        personas.chat("viewer", conversation, "青岛海边拍照");
        personas.claim();
        personas.chat("viewer", second, "青岛海边拍照");
        personas.claim();
        String other = personas.start("viewer", persona, null).get("id").toString();
        var queued = personas.chat("viewer", other, "青岛海边拍照");
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var futures = List.of(conversation, second).stream().map(source -> executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, java.util.concurrent.TimeUnit.SECONDS));
                personas.deleteConversation("viewer", source);
                return true;
            })).toList();
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            for (var future : futures) assertTrue(future.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(personas.memories("viewer", persona).isEmpty());
            assertEquals("CANCELLED", personas.job("viewer", queued.get("id").toString()).get("status"));
            assertEquals(1, personas.conversations("viewer", persona).size());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void sourceDeletionWaitsForAnEarlierCommittingReplyAndKeepsItHistorical() throws Exception {
        String id = memory(conversation, persona, "viewer", "青岛海边拍照", "CONFIRMED", 1);
        String other = personas.start("viewer", persona, null).get("id").toString();
        var job = personas.chat("viewer", other, "青岛海边拍照");
        var lease = personas.claim();
        var ready = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var deleting = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var completing = executor.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactions)
                    .execute(transaction -> {
                        var result = personas.complete(job.get("id").toString(), lease.get("lease_token").toString(), reply(id, ""));
                        ready.countDown();
                        try {
                            if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test_release_timeout");
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("test_interrupted");
                        }
                        return result;
                    }));
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            var deletion = executor.submit(() -> {
                deleting.countDown();
                personas.deleteConversation("viewer", conversation);
                return true;
            });
            assertTrue(deleting.await(10, java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> deletion.get(250, java.util.concurrent.TimeUnit.MILLISECONDS));
            release.countDown();
            var result = completing.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(deletion.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(personas.memories("viewer", persona).isEmpty());
            assertEquals("SUCCEEDED", personas.job("viewer", job.get("id").toString()).get("status"));
            assertEquals(result, personas.complete(job.get("id").toString(), lease.get("lease_token").toString(), reply(id, "")));
            assertEquals(2, items(personas.session("viewer", other).get("messages")).size());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void longHistoryKeepsPinnedIdentityCurrentInputAndRelevantMemoryWithinBudget() {
        String id = memory(conversation, persona, "viewer", "青岛海边拍照", "CONFIRMED", 1);
        for (int i = 0; i < 40; i++)
            db.update("INSERT INTO persona_message(id,conversation_id,role,body,created_at) VALUES (?,?,'assistant',?,?)",
                    UUID.randomUUID().toString(), conversation, "长对话填充".repeat(1000) + i, 10 + i);
        String newer = (String) personas.revise("owner", persona, version, Map.of("persona_markdown", "# 小溪\n新的大段表达", "correction", "合成新版")).get("version_id");
        personas.review("owner", persona, newer, true);
        var payload = json.decode(json.encode(claim("青岛海边拍照").get("payload")));
        assertEquals(version, payload.get("persona_version_id"));
        assertTrue(((Map<?, ?>) payload.get("persona")).get("persona_markdown").toString().contains("短句"));
        assertEquals("青岛海边拍照", payload.get("message"));
        assertEquals(id, items(payload.get("memories")).get(0).get("id"));
        assertTrue(items(payload.get("history")).size() < 24);
        var budget = (Map<?, ?>) payload.get("context_budget");
        assertTrue(assembler.estimate(payload) <= ((Number) budget.get("input_limit")).intValue());
        assertEquals(assembler.estimate(payload), budget.get("estimated_input_units"));
        assertEquals(17, ((Number) budget.get("history_outside_window")).intValue());
        assertEquals(24 - items(payload.get("history")).size(), budget.get("omitted_history"));
    }

    @Test
    void completedReplyExposesOnlyItsHistoricalContextCountsToItsOwner() {
        for (int i = 0; i < 26; i++)
            db.update("INSERT INTO persona_message(id,conversation_id,role,body,created_at) VALUES (?,?,'assistant',?,?)",
                    UUID.randomUUID().toString(), conversation, "合成短历史", i + 1);
        var job = claim("合成新问题");
        var payload = json.decode(json.encode(job.get("payload")));
        var budget = (Map<?, ?>) payload.get("context_budget");
        assertEquals(2, ((Number) budget.get("history_outside_window")).intValue());
        assertEquals(0, budget.get("omitted_history"));
        var forged = new LinkedHashMap<>(reply("", ""));
        forged.put("context_budget", Map.of("omitted_history", 999));
        var result = personas.complete(job.get("id").toString(), job.get("lease_token").toString(), forged);
        var messages = items(personas.session("viewer", conversation).get("messages"));
        var actual = messages.stream().filter(m -> m.get("id").equals(result.get("message_id"))).findFirst().orElseThrow();
        var scope = (Map<?, ?>) actual.get("context_scope");
        assertEquals(2L, scope.get("history_outside_window"));
        assertEquals(0L, scope.get("omitted_history"));
        assertEquals(Set.of("history_window_limit", "history_outside_window", "omitted_history", "omitted_memories", "omitted_experiences", "omitted_knowledge"), scope.keySet());
        assertThrows(ResponseStatusException.class, () -> personas.session("owner", conversation));
        assertEquals(scope, items(personas.session("viewer", conversation).get("messages")).stream()
                .filter(m -> m.get("id").equals(result.get("message_id"))).findFirst().orElseThrow().get("context_scope"));
    }

    @Test
    void legacyOrMalformedContextMetadataDoesNotBecomeZeroOmissions() {
        assertNull(internal.distilly.modules.conversation.domain.ContextScope.fromBudget(Map.of()));
        assertNull(internal.distilly.modules.conversation.domain.ContextScope.fromBudget(Map.of("omitted_history", -1)));
        var legacy = Map.of("omitted_history", 1, "omitted_memories", 0, "omitted_experiences", 0, "omitted_knowledge", 0,
                "source_body", "must not be exposed");
        var scope = internal.distilly.modules.conversation.domain.ContextScope.fromBudget(legacy);
        assertFalse(scope.containsKey("history_outside_window"));
        assertFalse(scope.containsKey("source_body"));
        assertEquals(1L, scope.get("omitted_history"));
    }

    @Test
    void completionVisibleBetweenSessionReadsDoesNotMislabelNewReplyAsLegacy() {
        var repository = org.mockito.Mockito.mock(internal.distilly.modules.conversation.repository.ConversationRepository.class);
        var access = org.mockito.Mockito.mock(internal.distilly.modules.persona.service.PersonaAccessService.class);
        org.mockito.Mockito.when(access.conversation("viewer", conversation)).thenReturn(Map.of("emotion", "{}"));
        var committed = new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.when(repository.findMessages(conversation)).thenAnswer(invocation -> {
            committed.set(true); // The atomic completion is visible when this SELECT sees the reply.
            return List.of(new LinkedHashMap<>(Map.<String, Object>of("id", "synthetic-reply", "role", "assistant", "citations", "[]")));
        });
        var result = json.encode(Map.of("message_id", "synthetic-reply", "context_budget", Map.of(
                "omitted_history", 1, "omitted_memories", 0, "omitted_experiences", 0, "omitted_knowledge", 0)));
        org.mockito.Mockito.when(repository.findCompletedChatResults(conversation, "viewer")).thenAnswer(invocation ->
                committed.get() ? List.of(Map.of("result", result)) : List.of());
        var service = new internal.distilly.modules.conversation.service.ConversationService(repository, json,
                org.mockito.Mockito.mock(internal.distilly.common.persistence.ResourceRepository.class), access,
                org.mockito.Mockito.mock(internal.distilly.modules.task.service.PersonaTaskService.class),
                org.mockito.Mockito.mock(internal.distilly.modules.memory.service.MemoryService.class));
        assertTrue(items(service.session("viewer", conversation).get("messages")).get(0).containsKey("context_scope"));
    }

    @Test
    void unknownExperiencesAndTemplateEvidenceAreNeverPromoted() {
        var content = Map.<String, Object>of("definition", definition("小溪"), "evidence", List.of(Map.of("material_id", "authorized", "quote", "记录提到在青岛海边拍照")));
        var experiences = ExperienceRecall.retrieve(content, "青岛拍照");
        assertTrue(experiences.stream().anyMatch(x -> x.get("origin").equals("fictional_setting")));
        assertTrue(experiences.stream().anyMatch(x -> x.get("origin").equals("authorized_source_excerpt")));
        assertTrue(ExperienceRecall.retrieve(content, "巴黎演唱会").isEmpty());
        var bound = new LinkedHashMap<>(content); bound.put("binding", Map.of("personality_id", "template"));
        assertTrue(ExperienceRecall.retrieve(bound, "青岛拍照").stream().noneMatch(x -> x.get("origin").equals("authorized_source_excerpt")));
        var job = claim("我们在巴黎演唱会见面的日期？");
        var payload = json.decode(json.encode(job.get("payload")));
        assertTrue(items(payload.get("memories")).isEmpty());
        assertTrue(items(payload.get("experiences")).isEmpty());
        assertThrows(ResponseStatusException.class, () -> personas.complete((String) job.get("id"), (String) job.get("lease_token"), reply("forged", "")));
        assertThrows(ResponseStatusException.class, () -> personas.complete((String) job.get("id"), (String) job.get("lease_token"), reply("", "evidence:forged")));
        assertDoesNotThrow(() -> personas.complete((String) job.get("id"), (String) job.get("lease_token"), reply("", "")));
    }

    @Test
    void oversizedMandatoryIdentityFailsVisiblyWithoutTruncationOrBlockingNextJob() {
        db.update("UPDATE persona_version SET content=? WHERE id=?", json.encode(Map.of("persona_markdown", "完整人格".repeat(7000), "definition", definition("小溪"))), version);
        var queued = personas.chat("viewer", conversation, "你好");
        assertNull(personas.claim());
        assertEquals("CONTEXT_LIMIT", personas.job("viewer", (String) queued.get("id")).get("error_code"));
        assertEquals("FAILED", personas.job("viewer", (String) queued.get("id")).get("status"));
    }

    @Test
    void allSourceFamiliesShareOneBudgetAndOversizedOptionalItemsDoNotHideSmallerMatches() {
        var personaContent = Map.<String, Object>of("persona_markdown", "固定风格", "definition", definition("小溪"));
        var memory = List.of(Map.<String, Object>of("id", "large", "body", "太长".repeat(10000)), Map.<String, Object>of("id", "small", "body", "青岛拍照"));
        var sources = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 12; i++) sources.add(Map.of("id", "knowledge" + i, "text", "共享知识".repeat(2000)));
        var payload = assembler.assemble(personaContent, version, "青岛拍照", Map.of(), true, List.of(), memory,
                List.of(Map.of("id", "experience", "text", "原创摄影设定", "origin", "fictional_setting")), sources, false);
        assertEquals(List.of("small"), items(payload.get("memories")).stream().map(x -> x.get("id")).toList());
        assertTrue(items(payload.get("knowledge")).size() < sources.size());
        assertEquals(1, ((Map<?, ?>) payload.get("context_budget")).get("omitted_memories"));
        assertEquals(sources.size() - items(payload.get("knowledge")).size(), ((Map<?, ?>) payload.get("context_budget")).get("omitted_knowledge"));
        assertTrue(assembler.estimate(payload) <= ((Number) ((Map<?, ?>) payload.get("context_budget")).get("input_limit")).intValue());
    }
    @Test
    void latestLongTurnUsesAvailableTotalBudgetAndRepeatedMessagesKeepChronology() {
        var content = Map.<String, Object>of("persona_markdown", "固定表达", "definition", definition("小溪"));
        var history = List.of(Map.<String, Object>of("role", "user", "body", "上文重要线索".repeat(300)),
                Map.<String, Object>of("role", "assistant", "body", "我会继续听"));
        var result = assembler.assemble(content, version, "然后呢？", Map.of(), true, history, List.of(), List.of(), List.of(), false);
        assertEquals(history, items(result.get("history")), "recent complete turn fits the total budget");
        var repeated = List.of(Map.<String, Object>of("role", "user", "body", "同样的问题"),
                Map.<String, Object>of("role", "assistant", "body", "中间的回答"),
                Map.<String, Object>of("role", "user", "body", "同样的问题"));
        result = assembler.assemble(content, version, "继续", Map.of(), true, repeated, List.of(), List.of(), List.of(), false);
        assertEquals(repeated, items(result.get("history")), "equal message bodies still have different chronological positions");
    }

    @Test
    void oversizedOptionalTurnDoesNotHideOlderSmallContext() {
        var content = Map.<String, Object>of("persona_markdown", "固定表达", "definition", definition("小溪"));
        var history = List.of(Map.<String, Object>of("role", "user", "body", "较早的小段必要背景"),
                Map.<String, Object>of("role", "assistant", "body", "无法容纳".repeat(10000)),
                Map.<String, Object>of("role", "user", "body", "近期输入"),
                Map.<String, Object>of("role", "assistant", "body", "近期回应"));
        var result = assembler.assemble(content, version, "继续", Map.of(), true, history, List.of(), List.of(), List.of(), false);
        assertEquals(List.of(history.get(0), history.get(2), history.get(3)), items(result.get("history")));
        assertEquals(1, ((Map<?, ?>) result.get("context_budget")).get("omitted_history"));
    }

    @Test
    void largeConfirmedCollectionRetainsOldRelevantMemoryWithBoundedOutput() {
        String expected = memory(conversation, persona, "viewer", "我们在青岛海边拍照", "CONFIRMED", 1);
        String source = (String) db.queryForMap("SELECT source_id FROM persona_memory WHERE id=?", expected).get("source_id");
        var rows = new ArrayList<Object[]>();
        for (int n = 0; n < 3000; n++) rows.add(new Object[] {UUID.randomUUID().toString(), persona, "viewer", "合成咖啡店座位编号" + n, source, n + 2L});
        db.batchUpdate("INSERT INTO persona_memory(id,persona_id,user_id,body,source_id,status,created_at) VALUES (?,?,?,?,?,'CONFIRMED',?)", rows);
        assertEquals(List.of(expected), recall.retrieve("viewer", persona, "青岛海边拍照").stream().map(x -> x.get("id")).toList());
        assertEquals(12, recall.retrieve("viewer", persona, "咖啡店座位").size());
        assertTrue(recall.retrieve("viewer", persona, "巴黎火星").isEmpty());
    }

    @Test
    void fixedRecallCorpusMeasuresHardNegativesAndReportsSemanticGaps() throws Exception {
        var root = java.nio.file.Path.of(System.getProperty("user.dir"));
        var fixture = root.resolve("tests/fixtures/memory_retrieval_cases.json");
        if (!java.nio.file.Files.exists(fixture)) fixture = root.getParent().resolve("tests/fixtures/memory_retrieval_cases.json");
        var corpus = json.decode(java.nio.file.Files.readString(fixture));
        assertEquals(Boolean.TRUE, corpus.get("synthetic"));
        String ownerConversation = (String) personas.start("owner", persona, null).get("id");
        String other = (String) personas.create("owner", project, definition("其他合成人物")).get("id");
        publish(other, "其他合成人物");
        String otherConversation = (String) personas.start("viewer", other, null).get("id");
        var labels = new HashMap<String, String>();
        long time = 1;
        for (var row : items(corpus.get("memories"))) {
            String scope = String.valueOf(row.getOrDefault("scope", "current"));
            String id = memory(scope.equals("other_user") ? ownerConversation : scope.equals("other_persona") ? otherConversation : conversation,
                    scope.equals("other_persona") ? other : persona, scope.equals("other_user") ? "owner" : "viewer",
                    (String) row.get("body"), (String) row.getOrDefault("status", "CONFIRMED"), time++);
            labels.put(id, (String) row.get("id"));
        }
        int strict = 0, strictMatches = 0, exploratory = 0, exploratoryMatches = 0;
        var failures = new ArrayList<String>();
        var results = new ArrayList<Map<String, Object>>();
        for (var testCase : items(corpus.get("cases"))) {
            var actual = recall.retrieve("viewer", persona, (String) testCase.get("query")).stream().map(row -> labels.get(row.get("id"))).collect(java.util.stream.Collectors.toSet());
            var expected = new HashSet<>((List<?>) testCase.get("expected_ids"));
            boolean exact = expected.equals(actual);
            if (Boolean.TRUE.equals(testCase.get("gate"))) {
                strict++; if (exact) strictMatches++; else failures.add(testCase.get("id") + ": expected=" + expected + ", actual=" + actual);
            }
            else { exploratory++; if (exact) exploratoryMatches++; }
            results.add(Map.of("case_id", testCase.get("id"), "gate", testCase.get("gate"), "exact", exact, "actual_ids", actual, "expected_ids", expected));
        }
        var report = Map.of("synthetic", true, "gated_cases", strict, "gated_exact_matches", strictMatches,
                "exploratory_cases", exploratory, "exploratory_exact_matches", exploratoryMatches, "cases", results);
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/memory-retrieval-evaluation.json"), json.encode(report));
        System.out.println("Synthetic recall corpus: " + strictMatches + "/" + strict + " gated exact matches; " + exploratoryMatches + "/" + exploratory + " exploratory exact matches");
        assertTrue(failures.isEmpty(), String.join("; ", failures));
    }

    @Test
    void mixedLanguageRecallBenchmarkKeepsScopeAndReportsAllocations() throws Exception {
        String sourceMemory = memory(conversation, persona, "viewer", "合成性能源消息", "CONFIRMED", 1);
        String source = (String) db.queryForMap("SELECT source_id FROM persona_memory WHERE id=?", sourceMemory).get("source_id");
        var text = new StringBuilder();
        for (int i = 0; i < 700; i++) text.appendCodePoint(0x4e00 + i);
        text.append(" anchorword");
        var rows = new ArrayList<Object[]>();
        for (int i = 0; i < 3000; i++) rows.add(new Object[] {UUID.randomUUID().toString(), persona, "viewer", text.toString(), source, i + 2L});
        db.batchUpdate("INSERT INTO persona_memory(id,persona_id,user_id,body,source_id,status,created_at) VALUES (?,?,?,?,?,'CONFIRMED',?)", rows);
        for (int i = 0; i < 2; i++) assertEquals(12, recall.retrieve("viewer", persona, "anchorword").size());
        var times = new ArrayList<Double>(); var allocations = new ArrayList<Long>();
        var bean = java.lang.management.ManagementFactory.getThreadMXBean();
        var measured = bean instanceof com.sun.management.ThreadMXBean b && b.isThreadAllocatedMemorySupported() ? (com.sun.management.ThreadMXBean) bean : null;
        if (measured != null && !measured.isThreadAllocatedMemoryEnabled()) measured.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().getId();
        for (int i = 0; i < 5; i++) {
            long bytes = measured == null ? -1 : measured.getThreadAllocatedBytes(thread);
            long start = System.nanoTime();
            var matches = recall.retrieve("viewer", persona, "anchorword");
            times.add((System.nanoTime() - start) / 1000000.0);
            allocations.add(measured == null ? -1L : measured.getThreadAllocatedBytes(thread) - bytes);
            assertEquals(12, matches.size());
            assertTrue(matches.stream().allMatch(row -> row.get("body").equals(text.toString())));
        }
        assertTrue(recall.retrieve("owner", persona, "anchorword").isEmpty());
        assertTrue(recall.retrieve("viewer", persona, "missingword").isEmpty());
        var report = Map.of("synthetic", true, "database", "isolated H2", "memory_count", 3001,
                "body_han_chars", 700, "samples_ms", times, "current_thread_allocated_bytes", allocations,
                "notes", "JIT-warmed local measurements; not production p95 or total RSS; no timing pass threshold");
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/memory-recall-performance.json"), json.encode(report));
        System.out.println("Mixed-language synthetic recall benchmark recorded (5 samples, no timing gate)");
    }

}
