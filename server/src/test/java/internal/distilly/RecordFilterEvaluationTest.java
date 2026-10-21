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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import static internal.distilly.common.validation.InputChecks.items;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Frozen synthetic literal-filter evaluation. No model, Worker, or historical corpus restoration. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:record-filter-evaluation;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "platform.demo=true", "platform.worker-token=filter-evaluation-synthetic-worker-token-32-characters",
        // This bypass is limited to synthetic fixture construction; requests under test remain read-only.
        "platform.max-active-tasks-per-user=0", "platform.task-submissions-per-minute=0",
        "platform.total-task-submissions-per-minute=0" })
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class RecordFilterEvaluationTest {
    @Autowired PlatformFixture platform;
    @Autowired PersonaFixture personas;
    @Autowired JsonCodec json;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    private static final String EMPTY = "目前没有匹配的已审核资料记录。可以先核对资料或补充信息。";
    private record Source(String key, String profile, String version, String claim, String text,
            String title, String section, boolean accessible) { }

    @Test
    void evaluateFrozenLiteralFiltersAgainstActualAuthorizedApi() throws Exception {
        String label = System.getProperty("record.filter.evaluation.label", "current");
        String requested = System.getProperty("record.filter.evaluation.split", "all");
        String mode = System.getProperty("record.filter.evaluation.mode", "candidate");
        assertTrue(label.matches("[A-Za-z0-9_-]{1,40}"), "invalid report label");
        assertTrue(Set.of("all", "development", "holdout").contains(requested), "invalid split");
        assertTrue(Set.of("baseline", "candidate").contains(mode), "invalid evaluation mode");
        var report = new LinkedHashMap<String, Object>();
        var splits = new LinkedHashMap<String, Object>();
        report.put("label", label); report.put("mode", mode); report.put("status", "RUNNING");
        report.put("started_at_utc", Instant.now().toString());
        report.put("scope", "literal_body_filters_AND_existing_lexical_question_before_top12");
        report.put("historical_corpus_restored", false); report.put("real_provider_calls", 0);
        report.put("workers_started", 0); report.put("fixture_admission_rate_limits_disabled", true);
        report.put("junit_assertion_scope", "HTTP validation/denial, contract metadata, access, immutable provenance, bounded response, fixed refusal and no writes; candidate literal-contract exact sets are asserted after saving the complete quality report; baseline reference misses are measured without claiming filter support");
        report.put("baseline_interpretation", "Actual filters on legacy API must be rejected with 422 (UNSUPPORTED); a separate request omitting filters is only a constraintless retrieval reference, not filter support. Invalid filters are LEGACY_REJECTION, not validated filters.");
        report.put("retrieval_service_class_sha256", classHash(PersonaKnowledgeService.class));
        report.put("record_service_class_sha256", classHash(RecordQuestionService.class));
        report.put("splits", splits);
        try {
            for (String split : List.of("development", "holdout")) {
                if (!requested.equals("all") && !requested.equals(split)) continue;
                byte[] raw = Files.readAllBytes(fixtureFile(split));
                var dataset = json.decode(new String(raw, StandardCharsets.UTF_8));
                assertEquals(split, dataset.get("split"), "fixture split mismatch");
                assertEquals(false, dataset.get("historical_corpus_restored"), "new corpus declaration required");
                var cases = items(dataset.get("cases"));
                assertFalse(cases.isEmpty(), "fixture is empty");
                var result = new LinkedHashMap<String, Object>();
                var rows = new ArrayList<Map<String, Object>>();
                var stats = new Stats();
                result.put("dataset_id", dataset.get("dataset_id")); result.put("split", split);
                result.put("fixture_sha256", sha(raw)); result.put("case_count", cases.size());
                result.put("cases", rows); splits.put(split, result);
                var ids = new LinkedHashSet<String>();
                try {
                    for (var testCase : cases) {
                        String id = required(testCase, "id");
                        assertTrue(ids.add(id), "duplicate fixture case ID");
                        var row = new LinkedHashMap<String, Object>();
                        row.put("id", id); row.put("family", required(testCase, "family"));
                        row.put("category", required(testCase, "category")); row.put("status", "FIXTURE_SETUP");
                        rows.add(row);
                        try { evaluateCase(testCase, row, stats, mode); }
                        catch (Exception | AssertionError error) {
                            stats.failedCases++; row.put("status", "SAFETY_OR_FIXTURE_FAILURE");
                            row.put("failure_type", error.getClass().getSimpleName()); throw error;
                        }
                    }
                    result.put("safety_status", "PASS");
                    result.put("quality_status", stats.filtered.fp + stats.filtered.fn + stats.noFilter.fp + stats.noFilter.fn == 0
                            ? "ALL_EXPECTATIONS_MET_ON_THIS_CORPUS" : "UNRESOLVED");
                } finally { result.put("metrics", stats.report(mode)); }
            }
            report.put("status", "COMPLETE"); report.put("safety_status", "PASS");
        } catch (Exception | AssertionError error) {
            report.put("status", "FAILED"); report.put("safety_status", "NOT_PASSED");
            report.put("failure_type", error.getClass().getSimpleName()); throw error;
        } finally {
            report.put("finished_at_utc", Instant.now().toString());
            Path target = Path.of("target", "record-filter-evaluation-" + label + ".json");
            Files.createDirectories(target.getParent()); Files.writeString(target, json.encode(report) + "\n");
        }
        // These new fixtures specify an explicit literal contract, unlike the separate
        // open semantic evaluation. Preserve every quality result before failing a regression.
        if (mode.equals("candidate"))
            for (Object raw : splits.values()) {
                var split = (Map<?, ?>) raw;
                assertEquals("ALL_EXPECTATIONS_MET_ON_THIS_CORPUS", split.get("quality_status"),
                        "literal filter regression; inspect saved report for all FP/FN");
            }
    }

    private void evaluateCase(Map<String, Object> testCase, Map<String, Object> row, Stats stats, String mode) throws Exception {
        String id = required(testCase, "id"), question = required(testCase, "question");
        assertTrue(question.length() <= 4000, "fixture question exceeds API bound: " + id);
        assertTrue(testCase.get("expected_status") instanceof Number, "fixture status missing: " + id);
        int expectedStatus = ((Number) testCase.get("expected_status")).intValue();
        assertTrue(Set.of(200, 422, 403).contains(expectedStatus), "fixture status invalid: " + id);
        boolean hasFilters = testCase.containsKey("filters");
        Set<String> expected = stringSet(testCase.get("expected_keys"));
        if (expectedStatus != 200) assertTrue(expected.isEmpty(), "rejected case must expect no sources: " + id);
        if (expectedStatus == 422) stats.invalid++;
        else if (expectedStatus == 403) stats.denied++;
        else if (hasFilters) stats.validFilter++; else stats.noFilterCount++;
        row.put("expected_status", expectedStatus); row.put("has_filters", hasFilters);
        row.put("expected_keys", new ArrayList<>(expected));
        String project = platform.createProject("owner", "新合成过滤评测空间").get("id").toString();
        if (expectedStatus != 403) platform.member("owner", project, "viewer", "VIEWER");
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
            // Invalid selections are injected deliberately to exercise the authoritative read SQL scope.
            if (selected) db.update("INSERT INTO persona_knowledge VALUES (?,?)", persona, source.profile());
        }
        assertTrue(keys.containsAll(expected), "expected key missing from fixture: " + id);
        for (Source source : sources.values())
            if (expected.contains(source.key())) assertTrue(source.accessible(), "fixture expects inaccessible source: " + id);
        var body = new LinkedHashMap<String, Object>(); body.put("question", question);
        if (hasFilters) body.put("filters", testCase.get("filters"));
        MockHttpServletResponse response = request(persona, body, sources, row, "actual", stats);
        row.put("actual_http_status", response.getStatus());
        if (mode.equals("baseline") && hasFilters) {
            assertEquals(422, response.getStatus(), "baseline must run against actual unsupported legacy API: " + id);
            assertHidden(response, sources, id, stats);
            row.put("api_support", expectedStatus == 422 ? "LEGACY_REJECTION" : "UNSUPPORTED");
            if (expectedStatus == 422) {
                stats.legacyRejection++; row.put("status", "LEGACY_REJECTION_NOT_FILTER_VALIDATION");
                row.put("returned_keys", List.of()); row.put("failure_categories", List.of()); return;
            }
            stats.unsupported++;
            response = request(persona, Map.of("question", question), sources, row, "reference", stats);
            row.put("reference_http_status", response.getStatus());
            row.put("reference_scope", expectedStatus == 403 ? "legacy_permission_reference_without_filters"
                    : "constraintless_reference_without_filters_NOT_filter_support");
        }
        assertEquals(expectedStatus, response.getStatus(), "unexpected API HTTP status: " + id);
        if (expectedStatus != 200) {
            assertHidden(response, sources, id, stats);
            row.put("returned_keys", List.of()); row.put("failure_categories", List.of());
            if (expectedStatus == 422) { stats.validatedInvalid++; row.put("status", "INVALID_FILTER_REJECTED"); }
            else { stats.deniedCorrect++; row.put("status", "ACCESS_DENIED_AS_EXPECTED"); }
            return;
        }
        if (mode.equals("candidate")) {
            var answer = json.decode(response.getContentAsString(StandardCharsets.UTF_8));
            assertEquals("RECORD_LITERAL_FILTERS_V1", answer.get("filter_contract"), "missing filter response contract: " + id);
            assertEquals(hasFilters ? testCase.get("filters") : Map.of(), answer.get("applied_filters"), "filter echo changed: " + id);
            row.put("filter_metadata_verified", true);
        }
        LinkedHashSet<String> returned = verifySuccess(response, persona, personaVersion, question, sources, row, id, stats);
        long tp = returned.stream().filter(expected::contains).count(), fp = returned.size() - tp, fn = expected.size() - tp;
        var failures = new ArrayList<String>();
        if (fn > 0) failures.add("miss"); if (fp > 0) failures.add("false_retrieval");
        row.put("tp", tp); row.put("fp", fp); row.put("fn", fn); row.put("failure_categories", failures);
        row.put("status", failures.isEmpty() ? "EXPECTED_RECORD_SET" : "QUALITY_UNRESOLVED");
        if (hasFilters) stats.filtered.observe(expected, returned, tp, fp, fn);
        else stats.noFilter.observe(expected, returned, tp, fp, fn);
    }

    private MockHttpServletResponse request(String persona, Map<String, Object> body, Map<String, Source> sources,
            Map<String, Object> row, String kind, Stats stats) throws Exception {
        var before = counts();
        var response = mvc.perform(post("/api/personas/" + persona + "/record-questions")
                .header("Authorization", "Bearer demo-viewer").contentType(MediaType.APPLICATION_JSON)
                .content(json.encode(body))).andReturn().getResponse();
        boolean noWrites = before.equals(counts()); row.put(kind + "_no_storage_writes", noWrites);
        safe(noWrites, stats, "read_write", "record read changed storage");
        return response;
    }

    private void assertHidden(MockHttpServletResponse response, Map<String, Source> sources, String id, Stats stats) throws Exception {
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        safe(!body.contains("record_cards"), stats, "access", "rejected response exposed record cards: " + id);
        safe(sources.values().stream().noneMatch(source -> body.contains(source.profile()) || body.contains(source.version())
                || body.contains(source.claim()) || body.contains(source.text())), stats, "access", "rejected response exposed source: " + id);
    }

    private LinkedHashSet<String> verifySuccess(MockHttpServletResponse response, String persona, String personaVersion,
            String question, Map<String, Source> sources, Map<String, Object> row, String id, Stats stats) throws Exception {
        assertEquals("no-store", response.getHeader("Cache-Control"), "record response cache policy: " + id);
        var answer = json.decode(response.getContentAsString(StandardCharsets.UTF_8));
        assertEquals("RECORD_CARDS_V1", answer.get("contract"), "record response contract: " + id);
        assertEquals(persona, answer.get("persona_id"), "record response persona: " + id);
        assertEquals(personaVersion, answer.get("persona_version_id"), "record persona version: " + id);
        assertEquals(question, answer.get("question"), "record response changed question: " + id);
        assertEquals(0, ((Number) answer.get("model_calls")).intValue(), "record route claims model calls: " + id);
        var cards = items(answer.get("record_cards"));
        assertEquals(cards.isEmpty() ? EMPTY : "找到以下当前可读记录，请核对来源；这些资料不证明人物亲历。",
                answer.get("answer"), "record answer changed from fixed non-generative template: " + id);
        assertTrue(cards.size() <= 12, "record source limit exceeded: " + id);
        var returned = new LinkedHashSet<String>(); var provenance = new ArrayList<Map<String, Object>>(); int chars = 0;
        for (var card : cards) {
            Source source = sources.get(card.get("profile_id"));
            safe(source != null && source.accessible(), stats, "access", "inaccessible/foreign source returned: " + id);
            boolean traceable = source.version().equals(card.get("version_id")) && source.claim().equals(card.get("claim_id"))
                    && (source.profile() + "/" + source.version() + "/" + source.claim()).equals(card.get("id"))
                    && source.text().equals(card.get("text")) && source.title().equals(card.get("title"))
                    && source.section().equals(card.get("section")) && "published_workspace_record".equals(card.get("origin"))
                    && "已审核工作资料，不证明人物亲历".equals(card.get("label"));
            safe(traceable, stats, "trace", "returned source is not the immutable original: " + id);
            assertTrue(returned.add(source.key()), "duplicate returned source: " + id); chars += source.text().length();
            provenance.add(Map.of("key", source.key(), "profile_id", source.profile(), "version_id", source.version(),
                    "claim_id", source.claim(), "text_sha256", sha(source.text().getBytes(StandardCharsets.UTF_8))));
        }
        assertTrue(chars <= 16000, "record character bound exceeded: " + id);
        row.put("returned_keys", new ArrayList<>(returned)); row.put("provenance", provenance);
        row.put("record_character_count", chars); row.put("model_calls", 0);
        row.put("access_and_trace_verified", true); return returned;
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
        return fixturePath("record_filter_" + split + "_v1.json");
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
    private static void safe(boolean condition, Stats stats, String kind, String message) {
        if (!condition) stats.violations.merge(kind, 1L, Long::sum);
        assertTrue(condition, message);
    }
    private static final class Stats {
        long validFilter, noFilterCount, invalid, denied, deniedCorrect, unsupported, legacyRejection, validatedInvalid, failedCases;
        final Map<String, Long> violations = new LinkedHashMap<>(Map.of("access", 0L, "trace", 0L, "read_write", 0L));
        final Metrics filtered = new Metrics(), noFilter = new Metrics();
        Map<String, Object> report(String mode) {
            var result = new LinkedHashMap<String, Object>();
            result.put("valid_filter_cases", validFilter); result.put("no_filter_cases", noFilterCount);
            result.put("invalid_request_cases", invalid); result.put("denied_cases", denied);
            result.put("correct_denied_cases", deniedCorrect); result.put("unsupported_filtered_cases", unsupported);
            result.put("legacy_rejection_NOT_filter_validation_cases", legacyRejection);
            result.put("candidate_validated_invalid_cases", validatedInvalid);
            result.put(mode.equals("baseline") ? "constraintless_reference_without_filters" : "filtered_api_quality", filtered.report());
            result.put("no_filter_api_quality", noFilter.report());
            result.put("failed_safety_or_fixture_cases", failedCases);
            result.put("access_violations", violations.get("access"));
            result.put("trace_violations", violations.get("trace"));
            result.put("read_write_violations", violations.get("read_write"));
            return result;
        }
    }
    private static final class Metrics {
        long allowed, positive, hit, exact, tp, fp, fn, expectedTotal, returnedTotal;
        long emptyExpected, emptyFalseReturn, emptyCorrect, emptyResponse;
        void observe(Set<String> expected, LinkedHashSet<String> returned, long truePositive, long falsePositive, long falseNegative) {
            allowed++; tp += truePositive; fp += falsePositive; fn += falseNegative;
            expectedTotal += expected.size(); returnedTotal += returned.size();
            if (!expected.isEmpty()) { positive++; if (truePositive > 0) hit++; }
            if (expected.equals(returned)) exact++;
            if (returned.isEmpty()) emptyResponse++;
            if (expected.isEmpty()) { emptyExpected++; if (returned.isEmpty()) emptyCorrect++; else emptyFalseReturn++; }
        }
        Map<String, Object> report() {
            var result = new LinkedHashMap<String, Object>();
            result.put("allowed_cases", allowed); result.put("positive_cases", positive);
            result.put("hit_at_12_cases", hit); result.put("hit_at_12_rate", positive == 0 ? null : (double) hit / positive);
            result.put("exact_record_set_cases", exact); result.put("exact_record_set_rate", allowed == 0 ? null : (double) exact / allowed);
            result.put("tp", tp); result.put("fp", fp); result.put("fn", fn);
            result.put("expected_record_count", expectedTotal); result.put("returned_record_count", returnedTotal);
            result.put("precision", tp + fp == 0 ? null : (double) tp / (tp + fp));
            result.put("recall", tp + fn == 0 ? null : (double) tp / (tp + fn));
            result.put("empty_expected_cases", emptyExpected); result.put("empty_expected_false_return_cases", emptyFalseReturn);
            result.put("correct_empty_expected_refusal_cases", emptyCorrect); result.put("empty_response_fixed_clarification_cases", emptyResponse);
            return result;
        }
    }
}
