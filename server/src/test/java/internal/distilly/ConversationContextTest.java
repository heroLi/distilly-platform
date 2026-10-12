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
