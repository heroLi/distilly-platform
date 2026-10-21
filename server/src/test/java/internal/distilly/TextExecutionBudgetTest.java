package internal.distilly;

import internal.distilly.common.json.JsonCodec;
import internal.distilly.modules.cost.repository.TextExecutionBudgetRepository;
import internal.distilly.modules.cost.service.TextExecutionBudgetService;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Public synthetic fixtures only; no Worker process, model request or provider credentials. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:text-execution-budget-test;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "platform.demo=true", "platform.worker-token=text-budget-synthetic-worker-token-32-characters" })
@AutoConfigureMockMvc
class TextExecutionBudgetTest {
    static final String WORKER = "Bearer text-budget-synthetic-worker-token-32-characters";
    static final String BASE = "/internal/text-budget";
    static final String DIGEST = "a".repeat(64);
    static final long RESERVE = 1_000_000_000L;
    static final long LIMIT = 10_000_000_000L;
    @Autowired PlatformFixture platform;
    @Autowired PersonaFixture personas;
    @Autowired JsonCodec json;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @Autowired TextExecutionBudgetService budget;
    @MockitoSpyBean TextExecutionBudgetRepository repository;
    String project;

    record Task(String queue, String id, String lease, String project) {}

    @BeforeEach
    void setup() {
        db.update("DELETE FROM text_execution_event");
        db.update("DELETE FROM text_execution_reservation");
        project = (String) platform.createProject("owner", "合成文字执行预算").get("id");
        platform.member("owner", project, "editor", "EDITOR");
        platform.member("owner", project, "viewer", "VIEWER");
    }

    Task task(String queue, String kind) { return task(queue, kind, project, "owner"); }
    Task task(String queue, String kind, String workspace, String creator) {
        String id = UUID.randomUUID().toString(), lease = UUID.randomUUID().toString();
        long until = System.currentTimeMillis() + 600_000;
        if (queue.equals("WORK")) {
            String profile = (String) platform.createProfile(creator, workspace, "合成文字主题").get("id");
            db.update("INSERT INTO job(id,project_id,profile_id,kind,status,payload,creator,lease_token,lease_until,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    id, workspace, profile, kind, "RUNNING", "{}", creator, lease, until, System.currentTimeMillis());
        } else {
            String persona = (String) personas.create(creator, workspace, Map.of("name", "合成人物", "family", "relationship",
                    "origin", "original", "expression", "短句", "personality", "温和", "boundaries", "不推断经历",
                    "background", "原创合成人物", "consent", false)).get("id");
            db.update("INSERT INTO persona_job(id,persona_id,kind,status,payload,creator,lease_token,lease_until,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    id, persona, kind, "RUNNING", "{}", creator, lease, until, System.currentTimeMillis());
        }
        return new Task(queue, id, lease, workspace);
    }
    Map<String, Object> reservation(Task task, String stage, String key) {
        return new LinkedHashMap<>(Map.of("queue", task.queue(), "job_id", task.id(), "lease_token", task.lease(),
                "stage", stage, "unit_digest", DIGEST, "request_key", key, "model", "deepseek-flash"));
    }
    ResultActions postWorker(String path, Map<String, Object> body) throws Exception {
        return mvc.perform(post(path).header("Authorization", WORKER).contentType(MediaType.APPLICATION_JSON)
                .content(json.encode(body)));
    }
    Map<String, Object> decoded(ResultActions result) throws Exception {
        return json.decode(result.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
    Map<String, Object> reserve(Task task, String stage, String key) throws Exception {
        return decoded(postWorker(BASE + "/reservations", reservation(task, stage, key)));
    }
    String id(Map<String, Object> result) { return (String) result.get("id"); }
    String path(String id, String action) { return BASE + "/reservations/" + id + "/" + action; }
    Map<String, Object> dispatch(Task task, String id) throws Exception {
        return decoded(postWorker(path(id, "dispatch"), Map.of("lease_token", task.lease())));
    }
    Map<String, Object> settleBody(Task task, String receipt, long actual) {
        return Map.of("lease_token", task.lease(), "outcome", "SETTLED", "evidence", "SYNTHETIC_USAGE_V1",
                "receipt_key", receipt, "actual_nano_cny", actual);
    }
    Map<String, Object> stateBody(Task task, String state) {
        return Map.of("lease_token", task.lease(), "outcome", state,
                "evidence", state.equals("RELEASED") ? "SYNTHETIC_NOT_DISPATCHED" : "SYNTHETIC_UNKNOWN");
    }
    Map<String, Object> outcome(Task task, String id, String state) throws Exception {
        return decoded(postWorker(path(id, "outcomes"), stateBody(task, state)));
    }
    Map<String, Object> settle(Task task, String id, String receipt, long actual) throws Exception {
        return decoded(postWorker(path(id, "outcomes"), settleBody(task, receipt, actual)));
    }
    long count(String table) { return db.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    long events(String id) { return db.queryForObject("SELECT COUNT(*) FROM text_execution_event WHERE reservation_id=?", Long.class, id); }
    String state(String id) { return db.queryForObject("SELECT status FROM text_execution_reservation WHERE id=?", String.class, id); }
    void totals(long held, long spent, long reservations) {
        assertEquals(held, db.queryForObject("SELECT COALESCE(SUM(reserved_nano_cny),0) FROM text_execution_reservation WHERE status IN ('RESERVED','DISPATCHED','UNCERTAIN')", Long.class));
        assertEquals(spent, db.queryForObject("SELECT COALESCE(SUM(actual_nano_cny),0) FROM text_execution_reservation WHERE status='SETTLED'", Long.class));
        assertEquals(reservations, count("text_execution_reservation"));
        assertTrue(held + spent <= LIMIT);
    }
    void tags(Map<String, Object> value) {
        assertEquals("SYNTHETIC_TEXT_EXECUTION_BUDGET_V1", value.get("contract"));
        assertEquals("INSTANCE_DATABASE_GLOBAL", value.get("scope"));
        assertEquals("CNY", value.get("currency")); assertEquals("nano_cny", value.get("unit"));
        assertEquals(true, value.get("synthetic")); assertEquals(false, value.get("is_bill"));
        assertEquals(false, value.get("real_egress_authorized"));
    }
    @SuppressWarnings("unchecked")
    void globalTotals(long held, long spent, long reservations, long unresolved) throws Exception {
        var view = decoded(mvc.perform(get(BASE).header("Authorization", WORKER))); tags(view); noLease(view);
        var summary = (Map<String, Object>) view.get("summary"); tags(summary);
        assertEquals(held, ((Number) summary.get("held_nano_cny")).longValue());
        assertEquals(spent, ((Number) summary.get("spent_nano_cny")).longValue());
        assertEquals(LIMIT, ((Number) summary.get("limit_nano_cny")).longValue());
        assertEquals(LIMIT - held - spent, ((Number) summary.get("available_nano_cny")).longValue());
        assertEquals(reservations, ((Number) summary.get("reservation_count")).longValue());
        assertEquals(unresolved, ((Number) summary.get("unresolved_count")).longValue());
    }
    void noLease(Object value) {
        if (value instanceof Map<?, ?> map) for (var entry : map.entrySet()) {
            assertNotEquals("lease_token", entry.getKey()); noLease(entry.getValue());
        } else if (value instanceof List<?> list) list.forEach(this::noLease);
    }
    <T> List<T> race(Callable<T> a, Callable<T> b) throws Exception {
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS)); return a.call(); });
            var second = pool.submit(() -> { ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS)); return b.call(); });
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        } finally { start.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    @Test
    void fixedReservationIsPersistedReplaySafeAndDoesNotExposeLease() throws Exception {
        Task task = task("WORK", "DISTILL");
        var first = reserve(task, "distill", "same-request");
        assertEquals("RESERVED", first.get("status"));
        assertEquals(RESERVE, ((Number) first.get("reserved_nano_cny")).longValue());
        assertEquals(id(first), id(reserve(task, "distill", "same-request")));
        noLease(first); tags(first);
        assertFalse(json.encode(first).contains(task.lease()));
        assertEquals(1, events(id(first))); totals(RESERVE, 0, 1);
        assertEquals("SYNTHETIC_TEXT_GLOBAL_V1", db.queryForObject("SELECT id FROM text_execution_budget_guard", String.class));
        var conflict = reservation(task, "distill", "same-request"); conflict.put("unit_digest", "b".repeat(64));
        postWorker(BASE + "/reservations", conflict).andExpect(status().isConflict());
        assertEquals(1, events(id(first))); totals(RESERVE, 0, 1);
    }

    @Test
    void allowedStagesAreBoundToTheActualQueueAndTaskKind() throws Exception {
        for (var spec : List.of(List.of("WORK", "DISTILL", "distill"), List.of("WORK", "ANSWER", "knowledge_answer"),
                List.of("PERSONA", "PERSONA", "persona_analysis"), List.of("PERSONA", "PERSONA", "persona_build"),
                List.of("PERSONA", "CHAT", "default"))) {
            Task task = task(spec.get(0), spec.get(1));
            assertEquals("RESERVED", reserve(task, spec.get(2), "valid-" + spec.get(2)).get("status"));
        }
        totals(5 * RESERVE, 0, 5);
    }

    @Test
    void wrongStageQueueOrNonTextKindCannotReserve() throws Exception {
        for (var spec : List.of(List.of("WORK", "DISTILL", "default"), List.of("WORK", "ANSWER", "distill"),
                List.of("PERSONA", "PERSONA", "knowledge_answer"), List.of("PERSONA", "CHAT", "persona_build"),
                List.of("PERSONA", "IMAGE", "default"))) {
            Task task = task(spec.get(0), spec.get(1));
            postWorker(BASE + "/reservations", reservation(task, spec.get(2), "wrong-stage"))
                    .andExpect(status().isUnprocessableEntity());
        }
        Task task = task("WORK", "DISTILL");
        var wrong = reservation(task, "distill", "wrong-queue"); wrong.put("queue", "PERSONA");
        postWorker(BASE + "/reservations", wrong).andExpect(status().isNotFound());
        totals(0, 0, 0); assertEquals(0, count("text_execution_event"));
    }

    @Test
    void missingExpiredWrongAndTerminalLeasesCannotReserveOrDispatch() throws Exception {
        Task task = task("WORK", "DISTILL");
        var wrong = reservation(task, "distill", "wrong-lease"); wrong.put("lease_token", UUID.randomUUID().toString());
        postWorker(BASE + "/reservations", wrong).andExpect(status().isConflict());
        String id = id(reserve(task, "distill", "valid-before-expiry"));
        db.update("UPDATE job SET lease_until=0 WHERE id=?", task.id());
        postWorker(path(id, "dispatch"), Map.of("lease_token", task.lease())).andExpect(status().isConflict());
        Task expired = task("PERSONA", "CHAT");
        db.update("UPDATE persona_job SET lease_until=0 WHERE id=?", expired.id());
        postWorker(BASE + "/reservations", reservation(expired, "default", "expired"))
                .andExpect(status().isConflict());
        Task terminal = task("WORK", "DISTILL");
        db.update("UPDATE job SET status='FAILED' WHERE id=?", terminal.id());
        postWorker(BASE + "/reservations", reservation(terminal, "distill", "terminal"))
                .andExpect(status().isConflict());
        totals(RESERVE, 0, 1);
    }

    @Test
    void dispatchGrantedExactlyOnceAndCannotReleaseAfterSending() throws Exception {
        Task task = task("WORK", "DISTILL"); String id = id(reserve(task, "distill", "dispatch"));
        assertEquals(Boolean.TRUE, dispatch(task, id).get("dispatch_permitted"));
        assertEquals("DISPATCHED", state(id)); assertEquals(2, events(id));
        postWorker(path(id, "dispatch"), Map.of("lease_token", task.lease())).andExpect(status().isConflict());
        postWorker(path(id, "outcomes"), stateBody(task, "RELEASED")).andExpect(status().isConflict());
        totals(RESERVE, 0, 1); assertEquals(2, events(id));
        assertEquals("SETTLED", settle(task, id, "explicit-zero-usage", 0).get("status"));
        totals(0, 0, 1);
    }

    @Test
    void releasedReservationIsIdempotentButCannotBeDispatchedOrRevived() throws Exception {
        Task task = task("PERSONA", "CHAT"); String id = id(reserve(task, "default", "release"));
        assertEquals("RELEASED", outcome(task, id, "RELEASED").get("status"));
        outcome(task, id, "RELEASED"); outcome(task, id, "UNCERTAIN");
        assertEquals("RELEASED", reserve(task, "default", "release").get("status"));
        assertEquals(2, events(id)); totals(0, 0, 1);
        postWorker(path(id, "dispatch"), Map.of("lease_token", task.lease())).andExpect(status().isConflict());
    }

    @Test
    void settlementAndExactReceiptReplaySpendOnlyActualUsage() throws Exception {
        Task task = task("PERSONA", "PERSONA"); String id = id(reserve(task, "persona_analysis", "settlement"));
        dispatch(task, id); var first = settle(task, id, "same-receipt", 123_456_789);
        assertEquals("SETTLED", first.get("status"));
        settle(task, id, "same-receipt", 123_456_789); outcome(task, id, "UNCERTAIN");
        postWorker(path(id, "outcomes"), settleBody(task, "same-receipt", 123_456_790)).andExpect(status().isConflict());
        assertEquals(3, events(id)); totals(0, 123_456_789, 1); noLease(first);
    }

    @Test
    void receiptKeyIsGlobalAndCannotSettleAnotherSpaceOrQueue() throws Exception {
        Task first = task("WORK", "DISTILL"); String a = id(reserve(first, "distill", "global-receipt-one"));
        dispatch(first, a); settle(first, a, "global-receipt", 100);
        String other = (String) platform.createProject("editor", "独立合成空间").get("id");
        Task second = task("PERSONA", "CHAT", other, "editor"); String b = id(reserve(second, "default", "global-receipt-two"));
        dispatch(second, b);
        postWorker(path(b, "outcomes"), settleBody(second, "global-receipt", 100)).andExpect(status().isConflict());
        assertEquals("DISPATCHED", state(b)); assertEquals(2, events(b)); totals(RESERVE, 100, 2);
    }

    @Test
    void uncertainFromBeforeOrAfterDispatchRemainsHeldUntilExplicitUsage() throws Exception {
        for (boolean send : List.of(false, true)) {
            Task task = task("WORK", "DISTILL"); String id = id(reserve(task, "distill", "uncertain-" + send));
            if (send) dispatch(task, id);
            outcome(task, id, "UNCERTAIN"); long events = events(id); outcome(task, id, "UNCERTAIN");
            assertEquals("UNCERTAIN", state(id)); assertEquals(events, events(id));
            postWorker(path(id, "outcomes"), stateBody(task, "RELEASED")).andExpect(status().isConflict());
            settle(task, id, "recovered-" + send, 100);
        }
        totals(0, 200, 2);
    }

    @Test
    void lateAccountingUsesOriginalLeaseWithoutRestoringOrFreeingTaskState() throws Exception {
        Task task = task("PERSONA", "CHAT"); String id = id(reserve(task, "default", "late-accounting"));
        dispatch(task, id); outcome(task, id, "UNCERTAIN");
        String replacement = UUID.randomUUID().toString();
        db.update("UPDATE persona_job SET lease_token=?,lease_until=0,status='FAILED' WHERE id=?", replacement, task.id());
        var wrong = settleBody(new Task(task.queue(), task.id(), replacement, project), "wrong-lease-receipt", 1);
        postWorker(path(id, "outcomes"), wrong).andExpect(status().isConflict());
        settle(task, id, "late-original-lease", 500);
        assertEquals("FAILED", db.queryForObject("SELECT status FROM persona_job WHERE id=?", String.class, task.id()));
        assertEquals(replacement, db.queryForObject("SELECT lease_token FROM persona_job WHERE id=?", String.class, task.id()));
        totals(0, 500, 1);
    }

    @Test
    void unresolvedReservationBlocksNewStageAndNewLeaseOfSameTask() throws Exception {
        Task task = task("PERSONA", "PERSONA"); String id = id(reserve(task, "persona_analysis", "pending-original"));
        postWorker(BASE + "/reservations", reservation(task, "persona_build", "pending-other-stage"))
                .andExpect(status().isConflict());
        String replacement = UUID.randomUUID().toString();
        db.update("UPDATE persona_job SET lease_token=?,lease_until=? WHERE id=?", replacement, System.currentTimeMillis() + 600_000, task.id());
        Task retried = new Task(task.queue(), task.id(), replacement, project);
        postWorker(BASE + "/reservations", reservation(retried, "persona_analysis", "pending-new-lease"))
                .andExpect(status().isConflict());
        outcome(task, id, "UNCERTAIN");
        postWorker(BASE + "/reservations", reservation(retried, "persona_build", "pending-unknown"))
                .andExpect(status().isConflict());
        totals(RESERVE, 0, 1);
    }

    @Test
    void settledSpendAndUnknownHoldsShareOneGlobalCapAcrossAllUsersSpacesAndQueues() throws Exception {
        for (int i = 0; i < 10; i++) {
            boolean persona = i % 2 == 0;
            String workspace = (String) platform.createProject(persona ? "owner" : "editor", "合成全局空间 " + i).get("id");
            Task task = task(persona ? "PERSONA" : "WORK", persona ? "CHAT" : "DISTILL", workspace, persona ? "owner" : "editor");
            String id = id(reserve(task, persona ? "default" : "distill", "global-" + i));
            dispatch(task, id);
            if (i % 3 == 0) outcome(task, id, "UNCERTAIN"); else settle(task, id, "global-bill-" + i, RESERVE);
        }
        totals(4 * RESERVE, 6 * RESERVE, 10);
        Task denied = task("WORK", "ANSWER");
        postWorker(BASE + "/reservations", reservation(denied, "knowledge_answer", "over-global-cap"))
                .andExpect(status().isConflict());
        assertEquals(10, count("text_execution_reservation")); globalTotals(4 * RESERVE, 6 * RESERVE, 10, 4);
    }

    @Test
    void ageLeaseExpiryAndCancellationDoNotAutomaticallyReleaseUnknownMoney() throws Exception {
        Task task = task("WORK", "DISTILL"); String id = id(reserve(task, "distill", "crash-held"));
        dispatch(task, id); outcome(task, id, "UNCERTAIN");
        db.update("UPDATE job SET status='CANCELLED',lease_until=0 WHERE id=?", task.id());
        decoded(mvc.perform(get(BASE).header("Authorization", WORKER)));
        totals(RESERVE, 0, 1); assertEquals("UNCERTAIN", state(id)); assertEquals(3, events(id));
    }

    @Test
    void oversizedOrMalformedUsageDoesNotReleaseHoldConsumeReceiptOrAppendEvent() throws Exception {
        Task task = task("WORK", "DISTILL"); String id = id(reserve(task, "distill", "bad-receipt")); dispatch(task, id);
        postWorker(path(id, "outcomes"), settleBody(task, "correctable-receipt", RESERVE + 1)).andExpect(status().isConflict());
        for (Object invalid : List.of(-1, 0.5, "1", true)) {
            var body = new LinkedHashMap<>(settleBody(task, "invalid-receipt", 1)); body.put("actual_nano_cny", invalid);
            postWorker(path(id, "outcomes"), body).andExpect(status().isUnprocessableEntity());
        }
        assertEquals("DISPATCHED", state(id)); assertEquals(2, events(id)); totals(RESERVE, 0, 1);
        settle(task, id, "correctable-receipt", RESERVE); totals(0, RESERVE, 1);
    }

    @Test
    void reservationRejectsAmountsOverridesUnknownFieldsBadDigestsAndMalformedKeys() throws Exception {
        Task task = task("WORK", "DISTILL");
        for (var change : List.of(Map.<String, Object>of("reserved_nano_cny", 1), Map.<String, Object>of("amount", 1),
                Map.<String, Object>of("model", "other-model"), Map.<String, Object>of("queue", "work"),
                Map.<String, Object>of("unit_digest", "A".repeat(64)), Map.<String, Object>of("unit_digest", "a".repeat(63)),
                Map.<String, Object>of("request_key", "bad.key"), Map.<String, Object>of("request_key", "中文"),
                Map.<String, Object>of("request_key", "x".repeat(81)), Map.<String, Object>of("request_key", ""))) {
            var body = reservation(task, "distill", "invalid-input"); body.putAll(change);
            postWorker(BASE + "/reservations", body).andExpect(status().isUnprocessableEntity());
        }
        for (String field : reservation(task, "distill", "missing").keySet()) {
            var body = reservation(task, "distill", "missing"); body.remove(field);
            postWorker(BASE + "/reservations", body).andExpect(status().isUnprocessableEntity());
        }
        totals(0, 0, 0); assertEquals(0, count("text_execution_event"));
    }

    @Test
    void dispatchAndOutcomesRejectUnknownFieldsEvidenceAndLeaseChanges() throws Exception {
        Task task = task("WORK", "DISTILL"); String id = id(reserve(task, "distill", "outcome-validation"));
        postWorker(path(id, "dispatch"), Map.of("lease_token", task.lease(), "allow", true)).andExpect(status().isUnprocessableEntity());
        var wrongLease = new Task(task.queue(), task.id(), UUID.randomUUID().toString(), project);
        postWorker(path(id, "outcomes"), stateBody(wrongLease, "RELEASED")).andExpect(status().isConflict());
        for (var body : List.of(Map.<String, Object>of("lease_token", task.lease(), "outcome", "RELEASED", "evidence", "SYNTHETIC_UNKNOWN"),
                Map.<String, Object>of("lease_token", task.lease(), "outcome", "UNCERTAIN", "evidence", "SYNTHETIC_UNKNOWN", "actual_nano_cny", 0),
                Map.<String, Object>of("lease_token", task.lease(), "outcome", "SETTLED", "evidence", "SYNTHETIC_USAGE_V1", "receipt_key", "receipt"),
                Map.<String, Object>of("lease_token", task.lease(), "outcome", "SETTLED", "evidence", "REAL_BILL", "receipt_key", "receipt", "actual_nano_cny", 0),
                settleBody(task, "bad.receipt", 0), settleBody(task, "a".repeat(161), 0))) {
            postWorker(path(id, "outcomes"), body).andExpect(status().isUnprocessableEntity());
        }
        assertEquals("RESERVED", state(id)); assertEquals(1, events(id)); totals(RESERVE, 0, 1);
    }

    @Test
    void workerAndUserTokensCannotCrossAuthenticationBoundaries() throws Exception {
        Task task = task("WORK", "DISTILL"); String id = id(reserve(task, "distill", "permission"));
        for (String auth : List.of("", "Bearer demo-owner", "Bearer demo-editor", "Bearer demo-viewer")) {
            mvc.perform(get(BASE).header("Authorization", auth)).andExpect(status().isUnauthorized());
            mvc.perform(post(BASE + "/reservations").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                    .content(json.encode(reservation(task, "distill", "not-worker")))).andExpect(status().isUnauthorized());
            mvc.perform(post(path(id, "dispatch")).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                    .content(json.encode(Map.of("lease_token", task.lease())))).andExpect(status().isUnauthorized());
            mvc.perform(post(path(id, "outcomes")).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                    .content(json.encode(stateBody(task, "RELEASED")))).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/projects/" + project + "/text-budget").header("Authorization", WORKER))
                .andExpect(status().isUnauthorized());
        totals(RESERVE, 0, 1); assertEquals(1, events(id));
    }

    @Test
    void ownerViewsContainOnlyTheirSpaceAndNeverRawLeaseOrGlobalAvailability() throws Exception {
        Task own = task("WORK", "DISTILL"); String ownId = id(reserve(own, "distill", "owner-view"));
        String other = (String) platform.createProject("editor", "其他用户合成空间").get("id");
        Task foreign = task("PERSONA", "CHAT", other, "editor"); String foreignId = id(reserve(foreign, "default", "foreign-view"));
        var result = decoded(mvc.perform(get("/api/projects/" + project + "/text-budget").header("Authorization", "Bearer demo-owner")));
        String encoded = json.encode(result); noLease(result);
        assertTrue(encoded.contains(ownId)); assertFalse(encoded.contains(foreignId)); assertFalse(encoded.contains(foreign.id()));
        assertFalse(encoded.contains(own.lease())); assertFalse(encoded.contains(foreign.lease()));
        assertFalse(encoded.contains("available_nano_cny")); tags(result);
        @SuppressWarnings("unchecked") var summary = (Map<String, Object>) result.get("summary");
        assertEquals(RESERVE, ((Number) summary.get("held_nano_cny")).longValue());
        assertEquals(0, ((Number) summary.get("spent_nano_cny")).longValue());
        assertEquals(1, ((Number) summary.get("reservation_count")).longValue());
        @SuppressWarnings("unchecked") var rows = (List<Map<String, Object>>) result.get("items");
        assertEquals(1, rows.size());
        for (String hidden : List.of("queue", "job_id", "project_id", "unit_digest", "request_key", "receipt_key"))
            assertFalse(rows.get(0).containsKey(hidden), "private task metadata: " + hidden);
        for (String actor : List.of("editor", "viewer"))
            mvc.perform(get("/api/projects/" + project + "/text-budget").header("Authorization", "Bearer demo-" + actor))
                    .andExpect(status().isForbidden());
        mvc.perform(get("/api/projects/" + other + "/text-budget").header("Authorization", "Bearer demo-owner"))
                .andExpect(status().isForbidden());
        var global = decoded(mvc.perform(get(BASE).header("Authorization", WORKER))); noLease(global);
        assertTrue(json.encode(global).contains(ownId)); assertTrue(json.encode(global).contains(foreignId));
    }

    @Test
    void racingSameRequestCreatesOneReservationAndInitialEvent() throws Exception {
        Task task = task("WORK", "DISTILL");
        var results = race(() -> reserve(task, "distill", "raced-same"), () -> reserve(task, "distill", "raced-same"));
        assertEquals(id(results.get(0)), id(results.get(1))); totals(RESERVE, 0, 1); assertEquals(1, count("text_execution_event"));
    }

    @Test
    void racingDispatchHasExactlyOnePermission() throws Exception {
        Task task = task("PERSONA", "CHAT"); String id = id(reserve(task, "default", "raced-dispatch"));
        var statuses = race(() -> postWorker(path(id, "dispatch"), Map.of("lease_token", task.lease())).andReturn().getResponse().getStatus(),
                () -> postWorker(path(id, "dispatch"), Map.of("lease_token", task.lease())).andReturn().getResponse().getStatus());
        assertEquals(1, statuses.stream().filter(s -> s == 200).count()); assertEquals(1, statuses.stream().filter(s -> s == 409).count());
        assertEquals("DISPATCHED", state(id)); assertEquals(2, events(id)); totals(RESERVE, 0, 1);
    }

    @Test
    void racingDifferentTasksAcrossSpacesCannotOverbookFinalGlobalSlot() throws Exception {
        for (int i = 0; i < 9; i++) {
            Task existing = task("WORK", "DISTILL"); String id = id(reserve(existing, "distill", "prefill-" + i));
            dispatch(existing, id); settle(existing, id, "prefill-receipt-" + i, RESERVE);
        }
        String other = (String) platform.createProject("editor", "争用合成空间").get("id");
        Task a = task("WORK", "ANSWER"), b = task("PERSONA", "CHAT", other, "editor");
        var statuses = race(() -> postWorker(BASE + "/reservations", reservation(a, "knowledge_answer", "raced-work")).andReturn().getResponse().getStatus(),
                () -> postWorker(BASE + "/reservations", reservation(b, "default", "raced-persona")).andReturn().getResponse().getStatus());
        assertEquals(1, statuses.stream().filter(s -> s == 200).count()); assertEquals(1, statuses.stream().filter(s -> s == 409).count());
        totals(RESERVE, 9 * RESERVE, 10); assertEquals(28, count("text_execution_event")); globalTotals(RESERVE, 9 * RESERVE, 10, 1);
    }

    @Test
    void racingReceiptReplaySettlesAndAppendsExactlyOnce() throws Exception {
        Task task = task("WORK", "DISTILL"); String id = id(reserve(task, "distill", "raced-settlement")); dispatch(task, id);
        var statuses = race(() -> postWorker(path(id, "outcomes"), settleBody(task, "raced-receipt", 99)).andReturn().getResponse().getStatus(),
                () -> postWorker(path(id, "outcomes"), settleBody(task, "raced-receipt", 99)).andReturn().getResponse().getStatus());
        assertEquals(List.of(200, 200), statuses); assertEquals(3, events(id)); totals(0, 99, 1);
    }

    @Test
    void requestKeyCannotBeReusedAcrossTasksProjectsOrQueues() throws Exception {
        Task first = task("WORK", "DISTILL"); reserve(first, "distill", "instance-request-key");
        String other = (String) platform.createProject("editor", "请求键隔离合成空间").get("id");
        Task second = task("PERSONA", "CHAT", other, "editor");
        postWorker(BASE + "/reservations", reservation(second, "default", "instance-request-key"))
                .andExpect(status().isConflict());
        totals(RESERVE, 0, 1); assertEquals(1, count("text_execution_event"));
    }

    @Test
    void completedStageAllowsNextStageButCannotMintDispatchFromItsOldRequest() throws Exception {
        Task task = task("PERSONA", "PERSONA"); String first = id(reserve(task, "persona_analysis", "analysis-call"));
        dispatch(task, first); settle(task, first, "analysis-receipt", 40);
        String second = id(reserve(task, "persona_build", "build-call"));
        assertNotEquals(first, second); dispatch(task, second);
        assertEquals("SETTLED", reserve(task, "persona_analysis", "analysis-call").get("status"));
        postWorker(path(first, "dispatch"), Map.of("lease_token", task.lease())).andExpect(status().isConflict());
        totals(RESERVE, 40, 2); globalTotals(RESERVE, 40, 2, 1);
    }

    @Test
    void concurrentNewKeysOnSameTaskCannotCreateTwoOutstandingCalls() throws Exception {
        Task task = task("PERSONA", "PERSONA");
        var statuses = race(() -> postWorker(BASE + "/reservations", reservation(task, "persona_analysis", "same-task-a")).andReturn().getResponse().getStatus(),
                () -> postWorker(BASE + "/reservations", reservation(task, "persona_build", "same-task-b")).andReturn().getResponse().getStatus());
        assertEquals(1, statuses.stream().filter(s -> s == 200).count()); assertEquals(1, statuses.stream().filter(s -> s == 409).count());
        totals(RESERVE, 0, 1); assertEquals(1, count("text_execution_event"));
    }

    @Test
    void settlementAndUnknownRaceCannotRegressTerminalSpendOrDuplicateTransition() throws Exception {
        Task task = task("WORK", "ANSWER"); String id = id(reserve(task, "knowledge_answer", "raced-terminal")); dispatch(task, id);
        var statuses = race(() -> postWorker(path(id, "outcomes"), settleBody(task, "raced-terminal-receipt", 77)).andReturn().getResponse().getStatus(),
                () -> postWorker(path(id, "outcomes"), stateBody(task, "UNCERTAIN")).andReturn().getResponse().getStatus());
        assertEquals(List.of(200, 200), statuses); assertEquals("SETTLED", state(id));
        assertTrue(Set.of(3L, 4L).contains(events(id))); totals(0, 77, 1); globalTotals(0, 77, 1, 0);
    }

    @Test
    void reservationAndInitialEventRollbackTogetherOnDatabaseWriteFailure() throws Exception {
        Task task = task("WORK", "DISTILL"); var once = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (once.getAndSet(false)) { assertEquals(1, count("text_execution_reservation")); throw new IllegalStateException("synthetic reserve event failure"); }
            return invocation.callRealMethod();
        }).when(repository).event(anyString(), anyString(), eq("RESERVED"), eq("SYNTHETIC_RESERVATION"), isNull(), anyLong());
        assertThrows(IllegalStateException.class, () -> budget.reserve(reservation(task, "distill", "atomic-reserve")));
        totals(0, 0, 0); assertEquals(0, count("text_execution_event"));
        reserve(task, "distill", "atomic-reserve"); totals(RESERVE, 0, 1);
    }

    @Test
    void failedDispatchEventCannotLeaveAConsumedSendingRightOrMissingEvent() throws Exception {
        Task task = task("WORK", "DISTILL"); String id = id(reserve(task, "distill", "atomic-dispatch"));
        var once = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (once.getAndSet(false)) { assertEquals("DISPATCHED", state(id)); throw new IllegalStateException("synthetic dispatch event failure"); }
            return invocation.callRealMethod();
        }).when(repository).event(anyString(), eq(id), eq("DISPATCHED"), eq("SYNTHETIC_DISPATCH"), isNull(), anyLong());
        assertThrows(IllegalStateException.class, () -> budget.dispatch(id, Map.of("lease_token", task.lease())));
        assertEquals("RESERVED", state(id)); assertEquals(1, events(id)); totals(RESERVE, 0, 1);
        assertEquals(true, dispatch(task, id).get("dispatch_permitted")); assertEquals(2, events(id));
    }

    @Test
    void settlementStateReceiptAndEventRollbackTogetherWithoutFreeingHold() throws Exception {
        Task task = task("PERSONA", "CHAT"); String id = id(reserve(task, "default", "atomic-settlement")); dispatch(task, id);
        var once = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (once.getAndSet(false)) { assertEquals("SETTLED", state(id)); throw new IllegalStateException("synthetic settlement event failure"); }
            return invocation.callRealMethod();
        }).when(repository).event(anyString(), eq(id), eq("SETTLED"), eq("SYNTHETIC_USAGE_V1"), eq(350L), anyLong());
        assertThrows(IllegalStateException.class, () -> budget.outcome(id, settleBody(task, "atomic-receipt", 350)));
        assertEquals("DISPATCHED", state(id)); assertEquals(2, events(id)); totals(RESERVE, 0, 1);
        assertNull(db.queryForObject("SELECT receipt_key FROM text_execution_reservation WHERE id=?", String.class, id));
        settle(task, id, "atomic-receipt", 350); assertEquals(3, events(id)); totals(0, 350, 1);
    }

    @Test
    void unknownTransitionAndEventRollbackTogetherWithoutImplicitRelease() throws Exception {
        Task task = task("PERSONA", "CHAT"); String id = id(reserve(task, "default", "atomic-unknown")); dispatch(task, id);
        var once = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (once.getAndSet(false)) { assertEquals("UNCERTAIN", state(id)); throw new IllegalStateException("synthetic unknown event failure"); }
            return invocation.callRealMethod();
        }).when(repository).event(anyString(), eq(id), eq("UNCERTAIN"), eq("SYNTHETIC_UNKNOWN"), isNull(), anyLong());
        assertThrows(IllegalStateException.class, () -> budget.outcome(id, stateBody(task, "UNCERTAIN")));
        assertEquals("DISPATCHED", state(id)); assertEquals(2, events(id)); totals(RESERVE, 0, 1);
        outcome(task, id, "UNCERTAIN"); assertEquals("UNCERTAIN", state(id)); assertEquals(3, events(id));
    }

}


@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:text-execution-budget-disabled-test;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "platform.demo=false", "platform.worker-token=text-budget-synthetic-worker-token-32-characters",
        "platform.users=[{\"id\":\"owner\",\"token\":\"synthetic-budget-owner-token-32-characters\"}]" })
@AutoConfigureMockMvc
class TextExecutionBudgetDisabledTest {
    @Autowired MockMvc mvc;
    @Test
    void nonDemoHidesEveryBudgetRouteEvenWithValidSyntheticAuthentication() throws Exception {
        String worker = "Bearer text-budget-synthetic-worker-token-32-characters";
        mvc.perform(get("/internal/text-budget").header("Authorization", worker)).andExpect(status().isNotFound());
        for (String path : List.of("/internal/text-budget/reservations", "/internal/text-budget/reservations/synthetic-id/dispatch",
                "/internal/text-budget/reservations/synthetic-id/outcomes"))
            mvc.perform(post(path).header("Authorization", worker).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isNotFound());
        mvc.perform(get("/api/projects/synthetic-project/text-budget")
                .header("Authorization", "Bearer synthetic-budget-owner-token-32-characters"))
                .andExpect(status().isNotFound());
    }
}
