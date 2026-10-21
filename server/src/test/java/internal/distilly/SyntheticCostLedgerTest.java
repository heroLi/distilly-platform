package internal.distilly;

import internal.distilly.common.json.JsonCodec;
import internal.distilly.modules.cost.repository.SyntheticCostRepository;
import internal.distilly.modules.cost.service.SyntheticCostService;
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
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static internal.distilly.common.validation.InputChecks.items;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:synthetic-cost-test;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "platform.demo=true", "platform.worker-token=synthetic-cost-worker-token-32-characters" })
@AutoConfigureMockMvc
class SyntheticCostLedgerTest {
    @Autowired PlatformFixture platform;
    @Autowired JsonCodec json;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @Autowired SyntheticCostService ledger;
    @MockitoSpyBean SyntheticCostRepository repository;
    String project;

    @BeforeEach
    void setup() {
        for (String table : List.of("synthetic_cost_event", "synthetic_cost_reservation", "audit", "member", "project"))
            db.update("DELETE FROM " + table);
        project = (String) platform.createProject("owner", "纯合成费用账本").get("id");
        platform.member("owner", project, "editor", "EDITOR");
        platform.member("owner", project, "viewer", "VIEWER");
    }

    String base() { return "/api/projects/" + project + "/synthetic-costs"; }
    String reservationPath(String id) { return base() + "/reservations/" + id; }
    ResultActions postBody(String actor, String path, Map<String, Object> body) throws Exception {
        return mvc.perform(post(path).header("Authorization", "Bearer demo-" + actor)
                .contentType(MediaType.APPLICATION_JSON).content(json.encode(body)));
    }
    Map<String, Object> decoded(ResultActions request) throws Exception {
        return json.decode(request.andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }
    Map<String, Object> reserve(String key, int fen) throws Exception {
        return decoded(postBody("owner", base() + "/reservations", Map.of("request_key", key, "reserved_fen", fen)));
    }
    Map<String, Object> outcome(String id, String key, String outcome, String evidence, Integer actual) throws Exception {
        return decoded(postBody("owner", reservationPath(id) + "/outcomes", callback(key, outcome, evidence, actual)));
    }
    Map<String, Object> callback(String key, String outcome, String evidence, Integer actual) {
        var body = new LinkedHashMap<String, Object>();
        body.put("callback_key", key); body.put("outcome", outcome); body.put("evidence", evidence);
        if (actual != null) body.put("actual_fen", actual);
        return body;
    }
    Map<String, Object> detail(String id) throws Exception {
        return decoded(mvc.perform(get(reservationPath(id)).header("Authorization", "Bearer demo-owner")));
    }
    void summary(int held, int spent, int available, int count, int unresolved) throws Exception {
        mvc.perform(get(base()).header("Authorization", "Bearer demo-owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.contract").value("SYNTHETIC_CNY_LEDGER_V1"))
                .andExpect(jsonPath("$.synthetic").value(true)).andExpect(jsonPath("$.limit_fen").value(1000))
                .andExpect(jsonPath("$.held_fen").value(held)).andExpect(jsonPath("$.spent_fen").value(spent))
                .andExpect(jsonPath("$.available_fen").value(available)).andExpect(jsonPath("$.reservation_count").value(count))
                .andExpect(jsonPath("$.unresolved_count").value(unresolved))
                .andExpect(jsonPath("$.real_egress_authorized").value(false));
    }
    String id(Map<String, Object> reservation) { return reservation.get("id").toString(); }
    Map<String, Long> writes() {
        var counts = new LinkedHashMap<String, Long>();
        for (String table : List.of("job", "persona_job", "persona_message", "persona_memory", "persona_conversation"))
            counts.put(table, db.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
        return counts;
    }

    @Test
    void emptyLedgerIsSyntheticAndDoesNotAuthorizeRealEgressOrCreateJobs() throws Exception {
        var before = writes();
        summary(0, 0, 1000, 0, 0);
        assertEquals(before, writes());
    }

    @Test
    void h2RuntimeDisablesDelayedWritesWithoutTestUrlOverride() throws Exception {
        try (var connection = java.util.Objects.requireNonNull(db.getDataSource()).getConnection()) {
            assertEquals("H2", connection.getMetaData().getDatabaseProductName());
            assertFalse(connection.getMetaData().getURL().toUpperCase(java.util.Locale.ROOT).contains("WRITE_DELAY"));
        }
        assertEquals("0", db.queryForObject(
                "SELECT setting_value FROM INFORMATION_SCHEMA.SETTINGS WHERE setting_name='WRITE_DELAY'", String.class));
    }

    @Test
    void reserveReplayIsIdempotentAndCannotChangeAmountOrReviveReleasedHold() throws Exception {
        var first = reserve("same-request", 300);
        assertEquals("RESERVED", first.get("status"));
        assertEquals(300, ((Number) first.get("reserved_fen")).intValue());
        assertNull(first.get("actual_fen"));
        assertEquals(id(first), id(reserve("same-request", 300)));
        int events = items(detail(id(first)).get("events")).size();
        postBody("owner", base() + "/reservations", Map.of("request_key", "same-request", "reserved_fen", 301))
                .andExpect(status().isConflict());
        assertEquals(events, items(detail(id(first)).get("events")).size());
        summary(300, 0, 700, 1, 1);
        outcome(id(first), "release", "RELEASED", "SIMULATED_ZERO_BILL", null);
        assertEquals("RELEASED", reserve("same-request", 300).get("status"));
        summary(0, 0, 1000, 1, 0);
    }

    @Test
    void settlementConvertsOnlyActualSpendAndCallbackReplayDoesNotAppendEvents() throws Exception {
        var before = writes();
        String reservation = id(reserve("settlement", 500));
        var result = outcome(reservation, "bill", "SETTLED", "SIMULATED_BILL", 220);
        assertEquals("SETTLED", result.get("status"));
        assertEquals(220, ((Number) result.get("actual_fen")).intValue());
        summary(0, 220, 780, 1, 0);
        var events = items(detail(reservation).get("events"));
        assertEquals(2, events.size());
        outcome(reservation, "bill", "SETTLED", "SIMULATED_BILL", 220);
        postBody("owner", reservationPath(reservation) + "/outcomes", callback("bill", "SETTLED", "SIMULATED_BILL", 221))
                .andExpect(status().isConflict());
        postBody("owner", reservationPath(reservation) + "/outcomes", callback("new-key", "SETTLED", "SIMULATED_BILL", 220))
                .andExpect(status().isConflict());
        assertEquals(events, items(detail(reservation).get("events")));
        assertEquals(before, writes());
    }

    @Test
    void zeroBillReleaseIsReplaySafeAndZeroSettlementIsWholeFen() throws Exception {
        String reservation = id(reserve("failed-call", 700));
        outcome(reservation, "zero-bill", "RELEASED", "SIMULATED_ZERO_BILL", null);
        outcome(reservation, "zero-bill", "RELEASED", "SIMULATED_ZERO_BILL", null);
        assertEquals(2, items(detail(reservation).get("events")).size());
        summary(0, 0, 1000, 1, 0);
        String second = id(reserve("zero-settlement", 1000));
        outcome(second, "zero-actual", "SETTLED", "SIMULATED_BILL", 0);
        summary(0, 0, 1000, 2, 0);
    }

    @Test
    void unknownCrashKeepsHoldRegardlessOfAgeUntilExplicitBillRecovery() throws Exception {
        String reservation = id(reserve("unknown-crash", 600));
        outcome(reservation, "timeout", "UNCERTAIN", "SIMULATED_UNKNOWN", null);
        db.update("UPDATE synthetic_cost_reservation SET created_at=0,updated_at=0 WHERE id=?", reservation);
        assertEquals("UNCERTAIN", detail(reservation).get("status"));
        summary(600, 0, 400, 1, 1);
        postBody("owner", base() + "/reservations", Map.of("request_key", "unsafe-retry", "reserved_fen", 500))
                .andExpect(status().isConflict());
        summary(600, 0, 400, 1, 1);
        outcome(reservation, "recovered-bill", "SETTLED", "SIMULATED_BILL", 450);
        summary(0, 450, 550, 1, 0);
        assertEquals("SETTLED", outcome(reservation, "timeout", "UNCERTAIN", "SIMULATED_UNKNOWN", null).get("status"));
        assertEquals(3, items(detail(reservation).get("events")).size());
    }

    @Test
    void uncertainCanRecoverToExplicitZeroBillRelease() throws Exception {
        String reservation = id(reserve("unknown-then-zero", 1000));
        outcome(reservation, "lost-result", "UNCERTAIN", "SIMULATED_UNKNOWN", null);
        summary(1000, 0, 0, 1, 1);
        outcome(reservation, "zero-recovered", "RELEASED", "SIMULATED_ZERO_BILL", null);
        summary(0, 0, 1000, 1, 0);
        assertEquals(3, items(detail(reservation).get("events")).size());
    }

    @Test
    void oversizedBillAndInvalidEvidenceRollBackWithoutLosingHoldOrAppendingEvent() throws Exception {
        String reservation = id(reserve("rollback", 500));
        var before = detail(reservation);
        postBody("owner", reservationPath(reservation) + "/outcomes", callback("too-large", "SETTLED", "SIMULATED_BILL", 501))
                .andExpect(status().isConflict());
        postBody("owner", reservationPath(reservation) + "/outcomes", callback("over-limit-bill", "SETTLED", "SIMULATED_BILL", 1001))
                .andExpect(status().isConflict());
        postBody("owner", reservationPath(reservation) + "/outcomes", callback("invalid-proof", "RELEASED", "SIMULATED_UNKNOWN", null))
                .andExpect(status().isUnprocessableEntity());
        assertEquals(before, detail(reservation));
        summary(500, 0, 500, 1, 1);
        // A failed transaction must not consume the callback idempotency key.
        outcome(reservation, "too-large", "SETTLED", "SIMULATED_BILL", 400);
        summary(0, 400, 600, 1, 0);
    }

    @Test
    void reservationAndInitialEventRollBackTogetherWhenEventWriteFails() throws Exception {
        var failOnce = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (failOnce.getAndSet(false)) {
                assertEquals(1L, db.queryForObject("SELECT COUNT(*) FROM synthetic_cost_reservation WHERE project_id=?",
                        Long.class, project));
                throw new IllegalStateException("synthetic injected reserve event failure");
            }
            return invocation.callRealMethod();
        }).when(repository).event(anyString(), eq(project), anyString(), isNull(), eq("RESERVED"),
                anyString(), isNull(), anyLong());
        assertThrows(IllegalStateException.class, () -> ledger.reserve("owner", project,
                Map.of("request_key", "reserve-atomic", "reserved_fen", 500)));
        assertEquals(0L, db.queryForObject("SELECT COUNT(*) FROM synthetic_cost_event WHERE project_id=?", Long.class, project));
        summary(0, 0, 1000, 0, 0);
        reserve("reserve-atomic", 500);
        summary(500, 0, 500, 1, 1);
    }

    @Test
    void stateTransitionAndCallbackRollBackTogetherWhenOutcomeEventWriteFails() throws Exception {
        String reservation = id(reserve("outcome-atomic", 500));
        var before = detail(reservation);
        var failOnce = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (failOnce.getAndSet(false)) {
                assertEquals("SETTLED", db.queryForObject("SELECT status FROM synthetic_cost_reservation WHERE id=?",
                        String.class, reservation));
                throw new IllegalStateException("synthetic injected outcome event failure");
            }
            return invocation.callRealMethod();
        }).when(repository).event(anyString(), eq(project), eq(reservation), eq("atomic-bill"), eq("SETTLED"),
                eq("SIMULATED_BILL"), eq(350), anyLong());
        assertThrows(IllegalStateException.class, () -> ledger.outcome("owner", project, reservation,
                callback("atomic-bill", "SETTLED", "SIMULATED_BILL", 350)));
        assertEquals(before, detail(reservation));
        assertEquals(0L, db.queryForObject("SELECT COUNT(*) FROM synthetic_cost_event WHERE project_id=? AND callback_key=?",
                Long.class, project, "atomic-bill"));
        summary(500, 0, 500, 1, 1);
        outcome(reservation, "atomic-bill", "SETTLED", "SIMULATED_BILL", 350);
        summary(0, 350, 650, 1, 0);
        assertEquals(2, items(detail(reservation).get("events")).size());
    }

    @Test
    void callbackKeyCannotBeReusedForAnotherReservation() throws Exception {
        String first = id(reserve("first", 200)), second = id(reserve("second", 200));
        outcome(first, "shared-callback", "SETTLED", "SIMULATED_BILL", 100);
        postBody("owner", reservationPath(second) + "/outcomes", callback("shared-callback", "SETTLED", "SIMULATED_BILL", 100))
                .andExpect(status().isConflict());
        assertEquals("RESERVED", detail(second).get("status"));
        assertEquals(1, items(detail(second).get("events")).size());
        summary(200, 100, 700, 2, 1);
    }

    @Test
    void reservationsCannotExceedCombinedHeldAndSpentLimit() throws Exception {
        String first = id(reserve("spent", 600));
        outcome(first, "spent-bill", "SETTLED", "SIMULATED_BILL", 600);
        reserve("held", 400);
        postBody("owner", base() + "/reservations", Map.of("request_key", "over-cap", "reserved_fen", 1))
                .andExpect(status().isConflict());
        summary(400, 600, 0, 2, 1);
    }

    <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> { ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS)); return first.call(); });
            var b = pool.submit(() -> { ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS)); return second.call(); });
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        } finally { start.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
    }
    int reserveStatus(String key, int fen) throws Exception {
        return postBody("owner", base() + "/reservations", Map.of("request_key", key, "reserved_fen", fen))
                .andReturn().getResponse().getStatus();
    }
    int callbackStatus(String reservation, String key, String state, String evidence, Integer actual) throws Exception {
        return postBody("owner", reservationPath(reservation) + "/outcomes", callback(key, state, evidence, actual))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void concurrentReservationsSerializeBudgetCap() throws Exception {
        // Distinct member locks ensure the shared project lock, rather than one account's lock, protects the cap.
        platform.member("owner", project, "editor", "OWNER");
        var statuses = race(() -> reserveStatus("parallel-one", 600),
                () -> postBody("editor", base() + "/reservations", Map.of("request_key", "parallel-two", "reserved_fen", 600))
                        .andReturn().getResponse().getStatus());
        assertEquals(1, statuses.stream().filter(s -> s == 200).count());
        assertEquals(1, statuses.stream().filter(s -> s == 409).count());
        summary(600, 0, 400, 1, 1);
    }

    @Test
    void concurrentSameRequestKeyCreatesExactlyOneHoldAndEvent() throws Exception {
        var results = race(() -> reserve("parallel-same", 600), () -> reserve("parallel-same", 600));
        assertEquals(id(results.get(0)), id(results.get(1)));
        assertEquals(1, items(detail(id(results.get(0))).get("events")).size());
        summary(600, 0, 400, 1, 1);
    }

    @Test
    void concurrentTerminalCallbacksAllowOneTransitionOnly() throws Exception {
        String reservation = id(reserve("callback-contention", 600));
        var statuses = race(() -> callbackStatus(reservation, "bill-wins", "SETTLED", "SIMULATED_BILL", 400),
                () -> callbackStatus(reservation, "zero-wins", "RELEASED", "SIMULATED_ZERO_BILL", null));
        assertEquals(1, statuses.stream().filter(s -> s == 200).count());
        assertEquals(1, statuses.stream().filter(s -> s == 409).count());
        var result = detail(reservation);
        assertEquals(2, items(result.get("events")).size());
        int spent = "SETTLED".equals(result.get("status")) ? 400 : 0;
        summary(0, spent, 1000 - spent, 1, 0);
    }

    @Test
    void concurrentExactCallbackReplayAppendsOneEventOnly() throws Exception {
        String reservation = id(reserve("callback-replay", 500));
        var statuses = race(() -> callbackStatus(reservation, "same-bill", "SETTLED", "SIMULATED_BILL", 300),
                () -> callbackStatus(reservation, "same-bill", "SETTLED", "SIMULATED_BILL", 300));
        assertEquals(List.of(200, 200), statuses);
        assertEquals(2, items(detail(reservation).get("events")).size());
        summary(0, 300, 700, 1, 0);
    }

    @Test
    void onlyOwnerCanReadReserveOrRecordOutcomesAndForeignWorkspaceCannotAccess() throws Exception {
        String reservation = id(reserve("owner-only", 100));
        for (String actor : List.of("editor", "viewer")) {
            mvc.perform(get(base()).header("Authorization", "Bearer demo-" + actor)).andExpect(status().isForbidden());
            mvc.perform(get(reservationPath(reservation)).header("Authorization", "Bearer demo-" + actor))
                    .andExpect(status().isForbidden());
            postBody(actor, base() + "/reservations", Map.of("request_key", "denied", "reserved_fen", 1))
                    .andExpect(status().isForbidden());
            postBody(actor, reservationPath(reservation) + "/outcomes", callback("denied", "RELEASED", "SIMULATED_ZERO_BILL", null))
                    .andExpect(status().isForbidden());
        }
        String originalProject = project;
        project = (String) platform.createProject("owner", "独立合成账本").get("id");
        mvc.perform(get(base()).header("Authorization", "Bearer demo-viewer")).andExpect(status().isForbidden());
        mvc.perform(get(reservationPath(reservation)).header("Authorization", "Bearer demo-owner"))
                .andExpect(status().isNotFound());
        postBody("owner", reservationPath(reservation) + "/outcomes", callback("foreign", "RELEASED", "SIMULATED_ZERO_BILL", null))
                .andExpect(status().isNotFound());
        summary(0, 0, 1000, 0, 0);
        reserve("owner-only", 1000);
        summary(1000, 0, 0, 1, 1);
        project = originalProject;
        summary(100, 0, 900, 1, 1);
    }

    @Test
    void reservationInputsRejectFractionsCoercionsUnknownFieldsAndInvalidKeys() throws Exception {
        for (Object invalid : List.of(0, -1, 1001, 0.5, 100.0, "100", true, 9223372036854775807L))
            postBody("owner", base() + "/reservations", Map.of("request_key", "invalid", "reserved_fen", invalid))
                    .andExpect(status().isUnprocessableEntity());
        for (Object invalid : List.of("", " ", "key.with.dot", "a".repeat(81), "中文", 12))
            postBody("owner", base() + "/reservations", Map.of("request_key", invalid, "reserved_fen", 100))
                    .andExpect(status().isUnprocessableEntity());
        postBody("owner", base() + "/reservations", Map.of("request_key", "unknown", "reserved_fen", 100, "model", "real"))
                .andExpect(status().isUnprocessableEntity());
        postBody("owner", base() + "/reservations", Map.of("request_key", "missing"))
                .andExpect(status().isUnprocessableEntity());
        summary(0, 0, 1000, 0, 0);
    }

    @Test
    void callbackInputsRejectUnknownFieldsInvalidTypesAndOutcomeEvidenceMismatch() throws Exception {
        String reservation = id(reserve("callback-validation", 500));
        var before = detail(reservation);
        for (Object invalid : List.of(-1, 0.1, 100.0, "100", false)) {
            var body = callback("invalid", "SETTLED", "SIMULATED_BILL", null);
            body.put("actual_fen", invalid);
            postBody("owner", reservationPath(reservation) + "/outcomes", body).andExpect(status().isUnprocessableEntity());
        }
        for (var body : List.of(callback("invalid", "SETTLED", "SIMULATED_BILL", null),
                callback("invalid", "SETTLED", "SIMULATED_UNKNOWN", 100),
                callback("invalid", "RELEASED", "SIMULATED_BILL", null),
                callback("invalid", "UNCERTAIN", "SIMULATED_ZERO_BILL", null),
                callback("invalid", "UNCERTAIN", "SIMULATED_UNKNOWN", 0),
                callback("invalid", "RELEASED", "SIMULATED_ZERO_BILL", 0),
                callback("invalid", "BOGUS", "SIMULATED_UNKNOWN", null),
                callback("bad.key", "SETTLED", "SIMULATED_BILL", 1)))
            postBody("owner", reservationPath(reservation) + "/outcomes", body).andExpect(status().isUnprocessableEntity());
        var extra = callback("extra", "SETTLED", "SIMULATED_BILL", 1); extra.put("real_egress_authorized", true);
        postBody("owner", reservationPath(reservation) + "/outcomes", extra).andExpect(status().isUnprocessableEntity());
        assertEquals(before, detail(reservation));
        summary(500, 0, 500, 1, 1);
    }
}

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:synthetic-cost-disabled-test;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "platform.demo=false", "platform.worker-token=synthetic-cost-worker-token-32-characters",
        "platform.users=[{\"id\":\"owner\",\"token\":\"synthetic-test-only-owner-token-32-characters\"}]" })
@AutoConfigureMockMvc
class SyntheticCostLedgerDisabledTest {
    @Autowired MockMvc mvc;
    static final String AUTH = "Bearer synthetic-test-only-owner-token-32-characters";

    @Test
    void nonDemoHidesAllLedgerRoutesIndependentlyOfAuthentication() throws Exception {
        // The configured credential above is a public synthetic fixture, never a real account token.
        String base = "/api/projects/synthetic-project/synthetic-costs";
        mvc.perform(get(base).header("Authorization", AUTH)).andExpect(status().isNotFound());
        mvc.perform(get(base + "/reservations/synthetic-reservation").header("Authorization", AUTH))
                .andExpect(status().isNotFound());
        mvc.perform(post(base + "/reservations").header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON)
                .content("{\"request_key\":\"synthetic\",\"reserved_fen\":100}"))
                .andExpect(status().isNotFound());
        mvc.perform(post(base + "/reservations/synthetic-reservation/outcomes").header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"callback_key\":\"synthetic\",\"outcome\":\"RELEASED\",\"evidence\":\"SIMULATED_ZERO_BILL\"}"))
                .andExpect(status().isNotFound());
    }
}
