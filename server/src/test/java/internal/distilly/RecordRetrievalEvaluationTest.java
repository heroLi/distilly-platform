package internal.distilly;

import internal.distilly.common.json.JsonCodec;
import internal.distilly.modules.knowledge.service.PersonaKnowledgeService;
import internal.distilly.modules.knowledge.service.RecordQuestionService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static internal.distilly.common.validation.InputChecks.items;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Actual authorized record-card API evaluation; quality misses remain explicit report failures. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:record-retrieval-evaluation;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "platform.demo=true", "platform.worker-token=retrieval-evaluation-synthetic-worker-token-32-characters",
        // Only fixture creation bypasses admission rate limits; no Worker/model is started.
        "platform.max-active-tasks-per-user=0", "platform.task-submissions-per-minute=0",
        "platform.total-task-submissions-per-minute=0" })
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class RecordRetrievalEvaluationTest {
    @Autowired PlatformFixture platform;
    @Autowired PersonaFixture personas;
    @Autowired JsonCodec json;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;

    private static final String EMPTY = "目前没有匹配的已审核资料记录。可以先核对资料或补充信息。";
    private record Source(String key, String profile, String version, String claim, String text,
            String title, String section, boolean accessible) { }

    @Test
    void evaluateIndependentCorporaWithoutTurningQualityMissesIntoAcceptance() throws Exception {
        String label = System.getProperty("record.evaluation.label", "current");
        String requested = System.getProperty("record.evaluation.split", "all");
        assertTrue(label.matches("[A-Za-z0-9_-]{1,40}"), "invalid report label");
        assertTrue(Set.of("all", "development", "holdout").contains(requested), "invalid split");
        var report = new LinkedHashMap<String, Object>();
        var splits = new LinkedHashMap<String, Object>();
        report.put("label", label);
        report.put("status", "RUNNING");
        report.put("started_at_utc", Instant.now().toString());
        report.put("scope", "current_published_selected_workspace_record_cards");
        report.put("historical_corpus_restored", false);
        report.put("real_provider_calls", 0);
        report.put("workers_started", 0);
        report.put("fixture_admission_rate_limits_disabled", true);
        report.put("junit_assertion_scope", "access, provenance, bounded response, fixed empty clarification and no writes; quality is measured separately");
        report.put("retrieval_service_class_sha256", classHash(PersonaKnowledgeService.class));
        report.put("record_service_class_sha256", classHash(RecordQuestionService.class));
        report.put("splits", splits);
        Path audit = fixturePath("record_retrieval_split_audit_v1.json");
        report.put("split_overlap_audit", Map.of("path", "tests/fixtures/record_retrieval_split_audit_v1.json",
                "sha256", Files.exists(audit) ? sha(Files.readAllBytes(audit)) : "not_available_at_run",
                "primary_denominator", "all frozen cases; no overlapping case removed",
                "supplementary_subset", "can join overlap case IDs with this report after algorithm freeze"));
        report.put("ranking_limit", "equal scores use generated source IDs; MRR describes this run, not a deterministic tie-break comparison");
        try {
            for (String split : List.of("development", "holdout")) {
                if (!requested.equals("all") && !requested.equals(split)) continue;
                Path file = fixtureFile(split);
                byte[] raw = Files.readAllBytes(file);
                var dataset = json.decode(new String(raw, StandardCharsets.UTF_8));
                assertEquals(split, dataset.get("split"), "fixture split mismatch");
                assertEquals(false, dataset.get("historical_corpus_restored"), "fresh corpus declaration required");
                var cases = items(dataset.get("cases"));
                assertFalse(cases.isEmpty(), "corpus cannot be empty");
                var rows = new ArrayList<Map<String, Object>>();
                var result = new LinkedHashMap<String, Object>();
                var totals = new Metrics();
                var categories = new LinkedHashMap<String, Metrics>();
                result.put("dataset_id", dataset.get("dataset_id"));
                result.put("split", split);
                result.put("fixture_sha256", sha(raw));
                result.put("case_count", cases.size());
                result.put("cases", rows);
                splits.put(split, result);
                var ids = new LinkedHashSet<String>();
                try {
                    for (var testCase : cases) {
                        String id = required(testCase, "id");
                        assertTrue(ids.add(id), "duplicate fixture case ID");
                        var row = new LinkedHashMap<String, Object>();
                        row.put("id", id);
                        row.put("family", required(testCase, "family"));
                        String category = required(testCase, "category");
                        row.put("category", category);
                        row.put("status", "FIXTURE_SETUP");
                        rows.add(row);
                        try {
                            evaluateCase(testCase, row, totals, categories.computeIfAbsent(category, ignored -> new Metrics()));
                        } catch (Exception | AssertionError error) {
                            row.put("status", "SAFETY_OR_FIXTURE_FAILURE");
                            row.put("failure_type", error.getClass().getSimpleName());
                            throw error;
                        }
                    }
                    result.put("safety_status", "PASS");
                    result.put("quality_status", totals.fp + totals.fn == 0 ? "ALL_EXPECTATIONS_MET_ON_THIS_CORPUS" : "UNRESOLVED");
                } finally {
                    result.put("metrics", totals.report());
                    var byCategory = new LinkedHashMap<String, Object>();
                    categories.forEach((name, metrics) -> byCategory.put(name, metrics.report()));
                    result.put("by_category", byCategory);
                }
            }
            report.put("status", "COMPLETE");
            report.put("safety_status", "PASS");
        } catch (Exception | AssertionError error) {
            report.put("status", "FAILED");
            report.put("safety_status", "NOT_PASSED");
            report.put("failure_type", error.getClass().getSimpleName());
            throw error;
        } finally {
            report.put("finished_at_utc", Instant.now().toString());
            Path target = Path.of("target", "record-retrieval-evaluation-" + label + ".json");
            Files.createDirectories(target.getParent());
            Files.writeString(target, json.encode(report) + "\n");
        }
    }

    private void evaluateCase(Map<String, Object> testCase, Map<String, Object> row, Metrics totals, Metrics category) throws Exception {
        String id = required(testCase, "id");
        String question = required(testCase, "question");
        assertTrue(question.length() <= 4000, "fixture question exceeds API bound: " + id);
        String access = testCase.getOrDefault("expect_access", "allowed").toString();
        assertTrue(Set.of("allowed", "denied").contains(access), "invalid fixture access: " + id);
        Set<String> expected = stringSet(testCase.get("expected_keys"));
        if (access.equals("denied")) assertTrue(expected.isEmpty(), "denied fixture must expect no records: " + id);
        String project = platform.createProject("owner", "新合成评测空间").get("id").toString();
        if (access.equals("allowed")) platform.member("owner", project, "viewer", "VIEWER");
        String persona = createPersona(project);
        String personaVersion = db.queryForObject("SELECT published_id FROM persona WHERE id=?", String.class, persona);
        var sources = new LinkedHashMap<String, Source>();
        var keys = new LinkedHashSet<String>();
        int index = 0;
        for (var document : items(testCase.get("documents"))) {
            String key = required(document, "key");
            assertTrue(keys.add(key), "duplicate fixture source key: " + id);
            String scope = document.getOrDefault("scope", "current").toString();
            assertTrue(Set.of("current", "foreign").contains(scope), "invalid fixture source scope: " + id);
            boolean selected = flag(document, "selected", true), published = flag(document, "published", true);
            String workspace = scope.equals("foreign") ? platform.createProject("owner", "独立合成隔离空间").get("id").toString() : project;
            Source source = createSource(workspace, document, key, ++index, selected && published && scope.equals("current"));
            sources.put(source.profile(), source);
            // Deliberately corrupt selections test the authoritative read query,
            // including foreign/unpublished rows which configure() would reject.
            if (selected) db.update("INSERT INTO persona_knowledge VALUES (?,?)", persona, source.profile());
        }
        assertTrue(keys.containsAll(expected), "expected key missing from fixture: " + id);
        for (Source source : sources.values())
            if (expected.contains(source.key())) assertTrue(source.accessible(), "fixture expects inaccessible source: " + id);
        var before = counts();
        var response = mvc.perform(post("/api/personas/" + persona + "/record-questions")
                .header("Authorization", "Bearer demo-viewer").contentType(MediaType.APPLICATION_JSON)
                .content(json.encode(Map.of("question", question)))).andReturn().getResponse();
        boolean noWrites = before.equals(counts());
        if (!noWrites) { totals.writeViolations++; category.writeViolations++; }
        assertTrue(noWrites, "record read changed storage: " + id);
        row.put("http_status", response.getStatus());
        row.put("expected_keys", new ArrayList<>(expected));
        if (access.equals("denied")) {
            totals.denied++; category.denied++;
            if (response.getStatus() != 403) { totals.accessViolations++; category.accessViolations++; }
            assertTrue(response.getStatus() == 403, "denied record request did not reject: " + id);
            String forbiddenBody = response.getContentAsString(StandardCharsets.UTF_8);
            boolean hidden = !forbiddenBody.contains("record_cards") && sources.values().stream().noneMatch(source ->
                    forbiddenBody.contains(source.profile()) || forbiddenBody.contains(source.version())
                    || forbiddenBody.contains(source.claim()) || forbiddenBody.contains(source.text()));
            if (!hidden) { totals.accessViolations++; category.accessViolations++; }
            assertTrue(hidden, "denied response exposed fixture source: " + id);
            totals.deniedCorrect++; category.deniedCorrect++;
            row.put("returned_keys", List.of());
            row.put("status", "ACCESS_DENIED_AS_EXPECTED");
            row.put("failure_categories", List.of());
            return;
        }
        assertTrue(response.getStatus() == 200, "allowed record request failed: " + id);
        assertEquals("no-store", response.getHeader("Cache-Control"), "record response cache policy: " + id);
        var answer = json.decode(response.getContentAsString(StandardCharsets.UTF_8));
        assertEquals("RECORD_CARDS_V1", answer.get("contract"), "record response contract: " + id);
        assertEquals(persona, answer.get("persona_id"), "record response persona: " + id);
        assertEquals(personaVersion, answer.get("persona_version_id"), "record persona version: " + id);
        assertTrue(question.equals(answer.get("question")), "record response changed question: " + id);
        assertEquals(0, ((Number) answer.get("model_calls")).intValue(), "record route claims model calls: " + id);
        var cards = items(answer.get("record_cards"));
        assertTrue((cards.isEmpty() ? EMPTY : "找到以下当前可读记录，请核对来源；这些资料不证明人物亲历。")
                .equals(answer.get("answer")), "record answer changed from fixed non-generative template: " + id);
        assertTrue(cards.size() <= 12, "record source limit exceeded: " + id);
        var returned = new LinkedHashSet<String>();
        var provenance = new ArrayList<Map<String, Object>>();
        int chars = 0;
        for (var card : cards) {
            Source source = sources.get(card.get("profile_id"));
            boolean visible = source != null && source.accessible();
            if (!visible) { totals.accessViolations++; category.accessViolations++; }
            assertTrue(visible, "inaccessible/foreign source returned: " + id);
            boolean traceable = source.version().equals(card.get("version_id"))
                    && source.claim().equals(card.get("claim_id"))
                    && (source.profile() + "/" + source.version() + "/" + source.claim()).equals(card.get("id"))
                    && source.text().equals(card.get("text")) && source.title().equals(card.get("title"))
                    && source.section().equals(card.get("section"))
                    && "published_workspace_record".equals(card.get("origin"))
                    && "已审核工作资料，不证明人物亲历".equals(card.get("label"));
            if (!traceable) { totals.provenanceViolations++; category.provenanceViolations++; }
            assertTrue(traceable, "returned source is not the current immutable original: " + id);
            assertTrue(returned.add(source.key()), "duplicate returned record: " + id);
            chars += source.text().length();
            provenance.add(Map.of("key", source.key(), "profile_id", source.profile(), "version_id", source.version(),
                    "claim_id", source.claim(), "text_sha256", sha(source.text().getBytes(StandardCharsets.UTF_8))));
        }
        assertTrue(chars <= 16000, "record character bound exceeded: " + id);
        if (cards.isEmpty()) {
            boolean fixed = EMPTY.equals(answer.get("answer"));
            if (!fixed) { totals.emptyClarificationViolations++; category.emptyClarificationViolations++; }
            assertTrue(fixed, "empty response is not fixed clarification: " + id);
        }
        row.put("returned_keys", new ArrayList<>(returned));
        row.put("provenance", provenance);
        row.put("record_character_count", chars);
        row.put("model_calls", 0);
        long tp = returned.stream().filter(expected::contains).count();
        long fp = returned.size() - tp, fn = expected.size() - tp;
        var failures = new ArrayList<String>();
        if (fn > 0) failures.add("miss");
        if (fp > 0) failures.add("false_retrieval");
        row.put("tp", tp); row.put("fp", fp); row.put("fn", fn);
        row.put("failure_categories", failures);
        row.put("status", failures.isEmpty() ? "EXPECTED_RECORD_SET" : "QUALITY_UNRESOLVED");
        totals.observe(expected, returned, tp, fp, fn);
        category.observe(expected, returned, tp, fp, fn);
    }

    private String createPersona(String project) {
        String persona = personas.create("owner", project, Map.of("name", "合成资料助手", "family", "relationship",
                "origin", "original", "personality", "简洁核对资料", "consent", false)).get("id").toString();
        personas.generate("owner", persona, List.of());
        var task = personas.claim();
        assertNotNull(task, "synthetic persona task not claimed");
        String version = personas.complete(task.get("id").toString(), task.get("lease_token").toString(), Map.of(
                "persona_markdown", "# 合成资料助手\n仅用于合成资料核对。", "analysis", Map.of("expression", "简洁", "inferences", List.of()),
                "evidence", List.of())).get("version_id").toString();
        personas.review("owner", persona, version, true);
        return persona;
    }

    private Source createSource(String project, Map<String, Object> document, String key, int index, boolean accessible) {
        String body = required(document, "text");
        assertTrue(body.length() >= 4 && body.length() <= 2000, "fixture document outside source bounds");
        String title = document.getOrDefault("title", "合成条目" + index).toString();
        String section = document.getOrDefault("section", "合成摘录").toString();
        assertTrue(!title.isBlank() && title.length() <= 160 && !section.isBlank() && section.length() <= 80,
                "fixture source metadata outside bounds");
        String profile = platform.createProfile("owner", project, title).get("id").toString();
        String material = platform.upload("owner", project, "synthetic.txt", body).get("id").toString();
        platform.distill("owner", profile, List.of(material));
        var task = platform.claim();
        assertNotNull(task, "synthetic source task not claimed");
        String version = platform.complete(task.get("id").toString(), task.get("lease_token").toString(), Map.of(
                "claims", List.of(Map.of("section", section, "text", body, "evidence", List.of(Map.of("material_id", material,
                    "chunk_id", material + ":0", "quote", body.substring(0, Math.min(body.length(), 300))))))))
                .get("version_id").toString();
        if (flag(document, "published", true)) platform.review("owner", version, true, "全新合成评测资料");
        var content = json.decode(db.queryForObject("SELECT content FROM profile_version WHERE id=?", String.class, version));
        String claim = items(content.get("claims")).get(0).get("id").toString();
        return new Source(key, profile, version, claim, body, title, section, accessible);
    }

    private Map<String, Long> counts() {
        var result = new LinkedHashMap<String, Long>();
        for (String table : List.of("job", "persona_job", "persona_message", "persona_memory", "persona_conversation", "audit", "profile_version"))
            result.put(table, db.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
        return result;
    }
    private static String required(Map<String, Object> body, String key) {
        Object value = body.get(key);
        assertTrue(value instanceof String && !((String) value).isBlank(), "required fixture string missing: " + key);
        return (String) value;
    }
    private static boolean flag(Map<String, Object> body, String key, boolean fallback) {
        if (!body.containsKey(key)) return fallback;
        assertTrue(body.get(key) instanceof Boolean, "fixture flag must be boolean: " + key);
        return Boolean.TRUE.equals(body.get(key));
    }
    private static Set<String> stringSet(Object raw) {
        assertTrue(raw instanceof List<?>, "fixture expected_keys must be an array");
        var result = new LinkedHashSet<String>();
        for (Object item : (List<?>) raw) {
            assertTrue(item instanceof String && !((String) item).isBlank(), "invalid fixture expected key");
            assertTrue(result.add((String) item), "duplicate fixture expected key");
        }
        return result;
    }
    private static Path fixtureFile(String split) {
        return fixturePath("record_retrieval_" + split + "_v1.json");
    }
    private static Path fixturePath(String filename) {
        Path module = Path.of("..", "tests", "fixtures", filename);
        return Files.exists(module) ? module : Path.of("tests", "fixtures", filename);
    }
    private static String sha(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
    private static String classHash(Class<?> type) throws Exception {
        try (var input = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            assertNotNull(input, "compiled class resource unavailable");
            return sha(input.readAllBytes());
        }
    }
    private static final class Metrics {
        long allowed, positive, hit, exact, tp, fp, fn, expectedTotal, returnedTotal;
        long emptyExpected, emptyFalseReturn, emptyCorrect, emptyResponse, denied, deniedCorrect;
        long accessViolations, provenanceViolations, writeViolations, emptyClarificationViolations;
        double reciprocalRank;
        void observe(Set<String> expected, LinkedHashSet<String> returned, long truePositive, long falsePositive, long falseNegative) {
            allowed++; tp += truePositive; fp += falsePositive; fn += falseNegative;
            expectedTotal += expected.size(); returnedTotal += returned.size();
            if (!expected.isEmpty()) {
                positive++;
                if (truePositive > 0) hit++;
                int rank = 0;
                for (String key : returned) { rank++; if (expected.contains(key)) { reciprocalRank += 1.0 / rank; break; } }
            }
            if (expected.equals(returned)) exact++;
            if (returned.isEmpty()) emptyResponse++;
            if (expected.isEmpty()) {
                emptyExpected++;
                if (returned.isEmpty()) emptyCorrect++; else emptyFalseReturn++;
            }
        }
        Map<String, Object> report() {
            var result = new LinkedHashMap<String, Object>();
            result.put("allowed_cases", allowed); result.put("positive_cases", positive);
            result.put("hit_at_12_cases", hit); result.put("hit_at_12_rate", positive == 0 ? null : (double) hit / positive);
            result.put("mrr_at_12", positive == 0 ? null : reciprocalRank / positive);
            result.put("exact_record_set_cases", exact); result.put("exact_record_set_rate", allowed == 0 ? null : (double) exact / allowed);
            result.put("tp", tp); result.put("fp", fp); result.put("fn", fn);
            result.put("expected_record_count", expectedTotal); result.put("returned_record_count", returnedTotal);
            result.put("precision", tp + fp == 0 ? null : (double) tp / (tp + fp));
            result.put("recall", tp + fn == 0 ? null : (double) tp / (tp + fn));
            result.put("empty_expected_cases", emptyExpected); result.put("empty_expected_false_return_cases", emptyFalseReturn);
            result.put("correct_empty_expected_refusal_cases", emptyCorrect); result.put("empty_response_fixed_clarification_cases", emptyResponse);
            result.put("denied_cases", denied); result.put("correct_denied_cases", deniedCorrect);
            result.put("access_violations", accessViolations); result.put("provenance_violations", provenanceViolations);
            result.put("read_write_violations", writeViolations); result.put("empty_clarification_violations", emptyClarificationViolations);
            return result;
        }
    }
}
